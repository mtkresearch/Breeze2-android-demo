package com.mtkresearch.gai_android.service;

import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import org.pytorch.executorch.LlamaModule;
import org.pytorch.executorch.LlamaCallback;
import com.executorch.ModelUtils;
import com.executorch.PromptFormat;
import com.executorch.ModelType;
import com.mtkresearch.gai_android.utils.ChatMessage;
import com.mtkresearch.gai_android.utils.ConversationManager;
import com.mtkresearch.gai_android.utils.AppConstants;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;

public class LLMEngineService extends BaseEngineService {
    private static final String TAG = "LLMEngineService";
    
    // Service state
    private String currentBackend = "none";
    private String preferredBackend = AppConstants.BACKEND_DEFAULT;
    private boolean hasSeenAssistantMarker = false;
    private final ConversationManager conversationManager;
    
    // Generation state
    private final AtomicBoolean isGenerating = new AtomicBoolean(false);
    private CompletableFuture<String> currentResponse = new CompletableFuture<>();
    private StreamingResponseCallback currentCallback = null;
    private final StringBuilder currentStreamingResponse = new StringBuilder();
    private ExecutorService executor;
    
    // CPU backend (LlamaModule)
    private LlamaModule mModule = null;
    private String modelPath = null;  // Set from intent
    
    // MTK backend state
    private static final Object MTK_LOCK = new Object();
    private static int mtkInitCount = 0;
    private static boolean isCleaningUp = false;
    private static final ExecutorService cleanupExecutor = Executors.newSingleThreadExecutor();
    
    static {
        // Only try to load MTK libraries if MTK backend is enabled
        if (AppConstants.MTK_BACKEND_ENABLED) {
            try {
                // Load libraries in order
                System.loadLibrary("sigchain");  // Load signal handler first
                Thread.sleep(100);  // Give time for signal handlers to initialize
                
                System.loadLibrary("llm_jni");
                AppConstants.MTK_BACKEND_AVAILABLE = true;
                Log.d(TAG, "Successfully loaded llm_jni library");
                
                // Register shutdown hook for cleanup
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        cleanupMTKResources();
                        cleanupExecutor.shutdownNow();
                    } catch (Exception e) {
                        Log.e(TAG, "Error in shutdown hook", e);
                    }
                }));
            } catch (UnsatisfiedLinkError | Exception e) {
                AppConstants.MTK_BACKEND_AVAILABLE = false;
                Log.w(TAG, "Failed to load native libraries, MTK backend will be disabled", e);
            }
        } else {
            Log.i(TAG, "MTK backend is disabled in AppConstants");
        }
    }

    public static boolean isMTKBackendAvailable() {
        return AppConstants.MTK_BACKEND_AVAILABLE && AppConstants.MTK_BACKEND_ENABLED;
    }

    public String getModelName() {
        if (modelPath == null) {
            if (currentBackend.equals(AppConstants.BACKEND_MTK)) {
                return "Breeze2";  // Default to Breeze2 for MTK backend
            }
            return "Unknown";
        }
        return com.mtkresearch.gai_android.utils.ModelUtils.getModelDisplayName(modelPath);
    }

    public LLMEngineService() {
        this.conversationManager = new ConversationManager();
    }

    public class LocalBinder extends BaseEngineService.LocalBinder<LLMEngineService> { }

    public interface StreamingResponseCallback {
        void onToken(String token);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new LocalBinder();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (intent.hasExtra("model_path")) {
                modelPath = intent.getStringExtra("model_path");
                Log.d(TAG, "Using model path: " + modelPath);
            }
            if (intent.hasExtra("preferred_backend")) {
                String newBackend = intent.getStringExtra("preferred_backend");
                if (!newBackend.equals(preferredBackend)) {
                    preferredBackend = newBackend;
                    // Force reinitialization if backend changed
                    releaseResources();
                    isInitialized = false;
                }
                Log.d(TAG, "Setting preferred backend to: " + preferredBackend);
            }
        }
        
        if (modelPath == null) {
            Log.e(TAG, "No model path provided in intent");
            stopSelf();
            return START_NOT_STICKY;
        }
        
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor();
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public CompletableFuture<Boolean> initialize() {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        
        // Create a timeout future
        CompletableFuture.delayedExecutor(AppConstants.LLM_INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .execute(() -> {
                if (!future.isDone()) {
                    future.complete(false);
                    Log.e(TAG, "Initialization timed out");
                }
            });
        
        // Run initialization in background
        CompletableFuture.supplyAsync(() -> {
            try {
                // Always release existing resources before initialization
                releaseResources();
                
                // Try MTK backend only if it's preferred
                if (preferredBackend.equals("mtk")) {
                    // Add delay before trying MTK initialization
                    Thread.sleep(200);
                    
                    if (initializeMTKBackend()) {
                        currentBackend = "mtk";
                        isInitialized = true;
                        Log.d(TAG, "Successfully initialized MTK backend");
                        future.complete(true);
                        return true;
                    }
                    Log.w(TAG, "MTK backend initialization failed");
                    
                    // Add delay before trying fallback
                    Thread.sleep(200);
                }

                // Try CPU backend if MTK failed or CPU is preferred
                if (preferredBackend.equals("cpu") || preferredBackend.equals("localCPU")) {
                    if (initializeLocalCPUBackend()) {
                        currentBackend = "localCPU";
                        isInitialized = true;
                        Log.d(TAG, "Successfully initialized CPU backend");
                        future.complete(true);
                        return true;
                    }
                    Log.w(TAG, "CPU backend initialization failed");
                }

                Log.e(TAG, "All backend initialization attempts failed");
                future.complete(false);
                return false;
            } catch (Exception e) {
                Log.e(TAG, "Error during initialization", e);
                future.completeExceptionally(e);
                return false;
            }
        });
        
        return future;
    }

    private static void cleanupMTKResources() {
        synchronized (MTK_LOCK) {
            if (isCleaningUp) return;
            isCleaningUp = true;
            
            try {
                Log.d("LLMEngineService", "Performing emergency cleanup of MTK resources");
                LLMEngineService tempInstance = new LLMEngineService();
                
                // Reset with timeout
                Future<?> resetFuture = cleanupExecutor.submit(() -> {
                    try {
                        tempInstance.nativeResetLlm();
                    } catch (Exception e) {
                        Log.w("LLMEngineService", "Error during emergency reset", e);
                    }
                });
                
                try {
                    resetFuture.get(AppConstants.MTK_NATIVE_OP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    Log.w("LLMEngineService", "Reset operation timed out");
                    resetFuture.cancel(true);
                }
                
                Thread.sleep(100);
                
                // Release with timeout
                Future<?> releaseFuture = cleanupExecutor.submit(() -> {
                    try {
                        tempInstance.nativeReleaseLlm();
                    } catch (Exception e) {
                        Log.w("LLMEngineService", "Error during emergency release", e);
                    }
                });
                
                try {
                    releaseFuture.get(AppConstants.MTK_NATIVE_OP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    Log.w("LLMEngineService", "Release operation timed out");
                    releaseFuture.cancel(true);
                }
                
                // Reset state
                mtkInitCount = 0;
                
                // Force garbage collection
                System.gc();
                Thread.sleep(100);
                
            } catch (Exception e) {
                Log.e("LLMEngineService", "Error during MTK cleanup", e);
            } finally {
                isCleaningUp = false;
            }
        }
    }

    private void forceCleanupMTKResources() {
        synchronized (MTK_LOCK) {
            if (isCleaningUp) return;
            isCleaningUp = true;
            
            try {
                Log.d(TAG, "Forcing cleanup of MTK resources");
                
                // Multiple cleanup attempts with timeouts
                for (int i = 0; i < 3; i++) {
                    Future<?> cleanupFuture = cleanupExecutor.submit(() -> {
                        try {
                            nativeResetLlm();
                            Thread.sleep(100);
                            nativeReleaseLlm();
                        } catch (Exception e) {
                            Log.e(TAG, "Error during forced cleanup attempt", e);
                        }
                    });
                    
                    try {
                        cleanupFuture.get(AppConstants.MTK_NATIVE_OP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException e) {
                        Log.w(TAG, "Cleanup attempt " + (i+1) + " timed out");
                        cleanupFuture.cancel(true);
                    }
                    
                    Thread.sleep(200);
                }
                
                // Reset state
                mtkInitCount = 0;
                
                // Force garbage collection
                System.gc();
                Thread.sleep(200);
                
            } catch (Exception e) {
                Log.e(TAG, "Error during forced cleanup", e);
            } finally {
                isCleaningUp = false;
            }
        }
    }

    private boolean initializeMTKBackend() {
        if (!AppConstants.MTK_BACKEND_AVAILABLE) {
            Log.d(TAG, "MTK backend disabled, skipping");
            return false;
        }

        synchronized (MTK_LOCK) {
            if (isCleaningUp) {
                Log.w(TAG, "Cannot initialize while cleanup is in progress");
                return false;
            }

            try {
                // Force cleanup if we've hit the max init attempts
                if (mtkInitCount >= AppConstants.MAX_MTK_INIT_ATTEMPTS) {
                    Log.w(TAG, "MTK init count exceeded limit, forcing cleanup");
                    forceCleanupMTKResources();
                    mtkInitCount = 0;
                    Thread.sleep(1000);  // Wait for cleanup to complete
                }

                // Add delay before initialization
                Thread.sleep(200);
                
                // Initialize signal handlers first
                try {
                    System.loadLibrary("sigchain");
                    Thread.sleep(100);
                } catch (UnsatisfiedLinkError e) {
                    Log.w(TAG, "Failed to load sigchain library", e);
                }

                Log.d(TAG, "Attempting MTK backend initialization...");
                
                boolean success = false;
                try {
                    // Reset state before initialization
                    nativeResetLlm();
                    Thread.sleep(100);
                    
                    // Initialize with conservative settings
                    success = nativeInitLlm("/data/local/tmp/llm_sdk/config_breezetiny_3b_instruct.yaml", true);
                    
                    if (!success) {
                        Log.e(TAG, "MTK initialization returned false");
                        cleanupAfterError();
                        return false;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error during MTK initialization", e);
                    cleanupAfterError();
                    return false;
                }
                
                if (success) {
                    mtkInitCount++;
                    Log.d(TAG, "MTK initialization successful. Init count: " + mtkInitCount);
                    return true;
                } else {
                    Log.e(TAG, "MTK initialization failed");
                    cleanupAfterError();
                    return false;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error initializing MTK backend", e);
                cleanupAfterError();
                return false;
            }
        }
    }

    private void cleanupAfterError() {
        try {
            // Force cleanup in a separate thread with timeout
            Thread cleanupThread = new Thread(() -> {
                try {
                    nativeResetLlm();
                    Thread.sleep(100);
                    nativeReleaseLlm();
                } catch (Exception e) {
                    Log.w(TAG, "Error during error cleanup", e);
                }
            });
            
            cleanupThread.start();
            cleanupThread.join(AppConstants.MTK_CLEANUP_TIMEOUT_MS);
            
            if (cleanupThread.isAlive()) {
                Log.w(TAG, "Cleanup thread timed out, interrupting");
                cleanupThread.interrupt();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during cleanup after error", e);
        }
    }

    private boolean initializeLocalCPUBackend() {
        try {
            Log.d(TAG, "Attempting Local CPU backend initialization...");

            if (mModule != null) {
                mModule.resetNative();
                mModule = null;
            }

            if (modelPath == null) {
                Log.e(TAG, "Model path is null, cannot initialize");
                return false;
            }

            // Initialize LlamaModule with model parameters
            mModule = new LlamaModule(
                ModelUtils.getModelCategory(ModelType.LLAMA_3_2),
                modelPath,
                AppConstants.LLM_TOKENIZER_PATH,
                AppConstants.LLM_TEMPERATURE
            );

            // Load the model
            int loadResult = mModule.load();
            if (loadResult != 0) {
                Log.e(TAG, "Failed to load model: " + loadResult);
                return false;
            }

            Log.d(TAG, "Local CPU backend initialized successfully");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error initializing Local CPU backend", e);
            return false;
        }
    }

    public CompletableFuture<String> generateResponse(String prompt) {
        if (!isInitialized) {
            return CompletableFuture.completedFuture(AppConstants.LLM_ERROR_RESPONSE);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                switch (currentBackend) {
                    case "mtk":
                        String response = nativeInference(prompt, 256, false);
                        nativeResetLlm();
                        nativeSwapModel(128);
                        return response;
                    case "localCPU":
                        try {
                            // Calculate sequence length based on prompt length, matching original implementation
                            int seqLen = (int)(prompt.length() * 0.75) + 64;  // Original Llama runner formula
                            
                            CompletableFuture<String> future = new CompletableFuture<>();
                            currentResponse = future;
                            
                            executor.execute(() -> {
                                try {
                                    mModule.generate(prompt, seqLen, new LlamaCallback() {
                                        @Override
                                        public void onResult(String result) {
                                            if (!isGenerating.get() || 
                                                result.equals(PromptFormat.getStopToken(ModelType.LLAMA_3_2))) {
                                                return;
                                            }
                                            currentStreamingResponse.append(result);
                                        }

                                        @Override
                                        public void onStats(float tps) {
                                            Log.d(TAG, String.format("Generation speed: %.2f tokens/sec", tps));
                                        }
                                    }, false);
                                    
                                    // Only complete if we haven't been stopped
                                    if (isGenerating.get()) {
                                        currentResponse.complete(currentStreamingResponse.toString());
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "Error in CPU generation", e);
                                    if (!currentResponse.isDone()) {
                                        currentResponse.completeExceptionally(e);
                                    }
                                } finally {
                                    isGenerating.set(false);
                                }
                            });
                            
                            return future.get(60000, TimeUnit.MILLISECONDS);
                        } catch (Exception e) {
                            Log.e(TAG, "Error in CPU streaming response", e);
                            throw e;
                        }
                    default:
                        return AppConstants.LLM_ERROR_RESPONSE;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error generating response", e);
                return AppConstants.LLM_ERROR_RESPONSE;
            }
        });
    }

    public CompletableFuture<String> generateStreamingResponse(String prompt, StreamingResponseCallback callback) {
        if (!isInitialized) {
            if (callback != null) {
                callback.onToken(AppConstants.LLM_ERROR_RESPONSE);
            }
            return CompletableFuture.completedFuture(AppConstants.LLM_ERROR_RESPONSE);
        }

        hasSeenAssistantMarker = false;
        currentCallback = callback;
        currentResponse = new CompletableFuture<>();
        currentStreamingResponse.setLength(0);
        isGenerating.set(true);
        
        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        
        CompletableFuture.runAsync(() -> {
            try {
                switch (currentBackend) {
                    case "mtk":
                        try {
                            // MTK backend uses raw prompt without formatting
                            executor.execute(() -> {
                                try {
                                    String response = nativeStreamingInference(prompt, 256, false, new TokenCallback() {
                                        @Override
                                        public void onToken(String token) {
                                            if (callback != null && isGenerating.get()) {
                                                callback.onToken(token);
                                                currentStreamingResponse.append(token);
                                            }
                                        }
                                    });
                                    
                                    // Only complete if we haven't been stopped
                                    if (isGenerating.get()) {
                                        currentResponse.complete(response);
                                        resultFuture.complete(response);
                                    }
                                    
                                    // Clean up MTK state
                                    try {
                                        nativeResetLlm();
                                        nativeSwapModel(128);
                                    } catch (Exception e) {
                                        Log.e(TAG, "Error resetting MTK state after generation", e);
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "Error in MTK streaming generation", e);
                                    if (!currentResponse.isDone()) {
                                        currentResponse.completeExceptionally(e);
                                        resultFuture.completeExceptionally(e);
                                    }
                                } finally {
                                    isGenerating.set(false);
                                }
                            });
                        } catch (Exception e) {
                            Log.e(TAG, "Error in MTK streaming response", e);
                            throw e;
                        }
                        break;
                        
                    case "localCPU":
                        // Only apply prompt formatting for local CPU backend
                        Log.d(TAG, "Formatted prompt for local CPU: " + prompt);
                        
                        // Calculate sequence length based on prompt length, matching original implementation
                        int seqLen = (int)(prompt.length() * 0.75) + 64;  // Original Llama runner formula
                        
                        executor.execute(() -> {
                            try {
                                mModule.generate(prompt, seqLen, new LlamaCallback() {
                                    @Override
                                    public void onResult(String token) {
                                        if (!isGenerating.get()) {
                                            return;
                                        }

                                        if (token == null || token.isEmpty()) {
                                            return;
                                        }

                                        // Handle both stop tokens - filter out both EOS tokens
                                        if (token.equals(PromptFormat.getStopToken(ModelType.LLAMA_3_2)) ||
                                            token.equals("<|end_of_text|>")) {
                                            Log.d(TAG, "Stop token detected: " + token);
                                            String finalResponse = currentStreamingResponse.toString();
                                            if (!currentResponse.isDone()) {
                                                currentResponse.complete(finalResponse);
                                                resultFuture.complete(finalResponse);
                                            }
                                            isGenerating.set(false);
                                            return;
                                        }

                                        // Handle streaming response
                                        if (callback != null) {
                                            callback.onToken(token);
                                        }
                                        currentStreamingResponse.append(token);
                                    }

                                    @Override
                                    public void onStats(float tps) {
                                        Log.d(TAG, String.format("Generation speed: %.2f tokens/sec", tps));
                                        // If we're getting stats but no tokens, check if we need to complete
                                        if (currentStreamingResponse.length() > 0 && !currentResponse.isDone() && !isGenerating.get()) {
                                            String finalResponse = currentStreamingResponse.toString();
                                            currentResponse.complete(finalResponse);
                                            resultFuture.complete(finalResponse);
                                        }
                                    }
                                }, false);
                                
                                // Add a delay and check if we need to complete the response
                                Thread.sleep(100);
                                if (!currentResponse.isDone() && currentStreamingResponse.length() > 0) {
                                    String finalResponse = currentStreamingResponse.toString();
                                    currentResponse.complete(finalResponse);
                                    resultFuture.complete(finalResponse);
                                }
                                
                            } catch (Exception e) {
                                Log.e(TAG, "Error in CPU streaming generation", e);
                                if (!currentResponse.isDone()) {
                                    currentResponse.completeExceptionally(e);
                                    resultFuture.completeExceptionally(e);
                                }
                            } finally {
                                isGenerating.set(false);
                            }
                        });

                        return currentResponse.get(AppConstants.LLM_GENERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS); 
                        
                    default:
                        String error = "Unsupported backend: " + currentBackend;
                        Log.e(TAG, error);
                        resultFuture.completeExceptionally(new IllegalStateException(error));
                }
                
                // Set up timeout that doesn't interrupt generation
                CompletableFuture.delayedExecutor(AppConstants.LLM_GENERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .execute(() -> {
                        if (!resultFuture.isDone() && isGenerating.get()) {
                            Log.w(TAG, "Generation taking longer than expected but continuing...");
                            // Don't stop generation, just notify about timeout
                            if (callback != null) {
                                callback.onToken("\n[Note: Generation is taking longer than usual but will continue...]");
                            }
                        }
                    });
                
            } catch (Exception e) {
                Log.e(TAG, "Error in streaming response", e);
                resultFuture.completeExceptionally(e);
            }
        });
        
        return resultFuture;
    }

    private void completeGeneration() {
        if (isGenerating.compareAndSet(true, false)) {
            String finalResponse = currentStreamingResponse.toString();
            if (currentResponse != null && !currentResponse.isDone()) {
                currentResponse.complete(finalResponse);
            }
            // Clean up resources
            currentCallback = null;
            System.gc(); // Request garbage collection for any lingering resources
        }
    }

    public void stopGeneration() {
        isGenerating.set(false);
        
        if (currentBackend.equals("mtk")) {
            try {
                nativeResetLlm();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping MTK generation", e);
            }
        } else if (mModule != null) {
            try {
                mModule.stop();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping CPU generation", e);
            }
        }

        if (currentResponse != null && !currentResponse.isDone()) {
            String finalResponse = currentStreamingResponse.toString();
            if (finalResponse.isEmpty()) {
                finalResponse = "[Generation stopped by user]";
            }
            currentResponse.complete(finalResponse);
        }
        
        // Clean up resources
        currentCallback = null;
        System.gc();
    }

    public void releaseResources() {
        synchronized (MTK_LOCK) {
            if (isCleaningUp) {
                Log.w(TAG, "Cleanup already in progress");
                return;
            }
            
            isCleaningUp = true;
            try {
                stopGeneration();
                
                // Release MTK resources if using MTK backend
                if (currentBackend.equals("mtk")) {
                    try {
                        // Add delay before cleanup
                        Thread.sleep(100);
                        nativeResetLlm();
                        Thread.sleep(100);
                        nativeReleaseLlm();
                        mtkInitCount = 0; // Reset init count
                        Log.d(TAG, "Released MTK resources");
                    } catch (Exception e) {
                        Log.e(TAG, "Error releasing MTK resources", e);
                        cleanupAfterError();
                    }
                }
                
                // Release CPU resources if using CPU backend
                if (mModule != null) {
                    try {
                        mModule.resetNative();
                        mModule = null;
                        Log.d(TAG, "Released CPU resources");
                    } catch (Exception e) {
                        Log.e(TAG, "Error releasing CPU resources", e);
                    }
                }
                
                // Reset state
                currentBackend = "none";
                isInitialized = false;
                System.gc(); // Request garbage collection
                
                Log.d(TAG, "All resources released");
            } catch (Exception e) {
                Log.e(TAG, "Error during cleanup", e);
            } finally {
                isCleaningUp = false;
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        
        // Run cleanup with timeout
        Future<?> cleanupFuture = cleanupExecutor.submit(() -> {
            try {
                cleanupMTKResources();
                releaseResources();
            } catch (Exception e) {
                Log.e(TAG, "Error during service cleanup", e);
            }
        });
        
        try {
            cleanupFuture.get(AppConstants.MTK_CLEANUP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            Log.w(TAG, "Service cleanup timed out");
            cleanupFuture.cancel(true);
        } catch (Exception e) {
            Log.e(TAG, "Error waiting for cleanup", e);
        }
        
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }

    public String getCurrentBackend() {
        return currentBackend;
    }

    public String getPreferredBackend() {
        return preferredBackend;
    }

    // Native methods for MTK backend
    private native boolean nativeInitLlm(String yamlConfigPath, boolean preloadSharedWeights);
    private native String nativeInference(String inputString, int maxResponse, boolean parsePromptTokens);
    private native String nativeStreamingInference(String inputString, int maxResponse, boolean parsePromptTokens, TokenCallback callback);
    private native void nativeReleaseLlm();
    private native boolean nativeResetLlm();
    private native boolean nativeSwapModel(int tokenSize);

    public interface TokenCallback {
        void onToken(String token);
    }
} 