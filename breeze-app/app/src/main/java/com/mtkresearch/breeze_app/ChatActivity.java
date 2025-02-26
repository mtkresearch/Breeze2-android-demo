package com.mtkresearch.breeze_app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.Toast;
import android.widget.ImageButton;
import android.widget.CheckBox;
import android.app.AlertDialog;

import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.view.GravityCompat;

import com.mtkresearch.breeze_app.utils.AudioListAdapter;
import com.mtkresearch.breeze_app.utils.ChatHistory;
import com.mtkresearch.breeze_app.utils.ChatMediaHandler;
import com.mtkresearch.breeze_app.utils.ChatMessageAdapter;
import com.mtkresearch.breeze_app.databinding.ActivityChatBinding;
import com.mtkresearch.breeze_app.utils.ChatMessage;

import java.io.File;

import com.mtkresearch.breeze_app.service.ASREngineService;
import com.mtkresearch.breeze_app.service.LLMEngineService;
import com.mtkresearch.breeze_app.service.TTSEngineService;
import com.mtkresearch.breeze_app.service.VLMEngineService;
import com.mtkresearch.breeze_app.utils.IntroDialog;
import com.mtkresearch.breeze_app.utils.UiUtils;
import com.mtkresearch.breeze_app.utils.AppConstants;

import java.io.IOException;
import android.content.pm.PackageManager;

import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import android.util.Log;

import com.mtkresearch.breeze_app.utils.FileUtils;
import com.mtkresearch.breeze_app.utils.ChatUIStateHandler;
import com.mtkresearch.breeze_app.utils.ConversationManager;
import com.mtkresearch.breeze_app.utils.ChatHistoryManager;
import com.mtkresearch.breeze_app.utils.ChatHistoryAdapter;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import android.graphics.Color;

import com.executorch.ModelType;
import com.mtkresearch.breeze_app.utils.PromptManager;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import android.os.Handler;
import android.os.Looper;

import com.mtkresearch.breeze_app.utils.ModelUtils;

public class ChatActivity extends AppCompatActivity implements ChatMessageAdapter.OnSpeakerClickListener {
    private static final String TAG = AppConstants.CHAT_ACTIVITY_TAG;

    // Request codes
    private static final int PERMISSION_REQUEST_CODE = AppConstants.PERMISSION_REQUEST_CODE;
    private static final int PICK_IMAGE_REQUEST = AppConstants.PICK_IMAGE_REQUEST;
    private static final int CAPTURE_IMAGE_REQUEST = AppConstants.CAPTURE_IMAGE_REQUEST;
    private static final int PICK_FILE_REQUEST = AppConstants.PICK_FILE_REQUEST;

    // Constants for alpha values
    private static final float ENABLED_ALPHA = AppConstants.ENABLED_ALPHA;
    private static final float DISABLED_ALPHA = AppConstants.DISABLED_ALPHA;

    // View Binding
    private ActivityChatBinding binding;

    // Handlers
    private ChatMediaHandler mediaHandler;
    private ChatUIStateHandler uiHandler;
    private ConversationManager conversationManager;
    private ChatHistoryManager historyManager;

    // Adapters
    private ChatMessageAdapter chatAdapter;
    private AudioListAdapter audioListAdapter;
    private ChatHistoryAdapter historyAdapter;

    // Services
    private LLMEngineService llmService;
    private VLMEngineService vlmService;
    private ASREngineService asrService;
    private TTSEngineService ttsService;

    private DrawerLayout drawerLayout;

    private static final int CONVERSATION_HISTORY_MESSAGE_LOOKBACK = AppConstants.CONVERSATION_HISTORY_LOOKBACK;

    // Add promptId field at the top of the class
    private int promptId = 0;

    private int titleTapCount = 0;
    private static final int TAPS_TO_SHOW_MAIN = AppConstants.TAPS_TO_SHOW_MAIN;
    private static final long TAP_TIMEOUT_MS = AppConstants.TAP_TIMEOUT_MS;
    private long lastTapTime = 0;

    // Add these fields at the top of the class with other fields
    private boolean llmServiceReady = false;
    private boolean vlmServiceReady = false;
    private boolean asrServiceReady = false;
    private boolean ttsServiceReady = false;

    // Add a flag to track MTK support status
    private static boolean mtkBackendChecked = false;
    private static boolean mtkBackendSupported = true;

    // Add new fields for initialization state
    private boolean isInitializing = false;
    private final Object initLock = new Object();
    private static final int INIT_DELAY_MS = AppConstants.INIT_DELAY_MS;

    private boolean hasReceivedResponse = false;  // Add class field

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initializeViews();
        initializeHandlers();
        
        // Show intro dialog first, services will initialize after dialog is dismissed
        showIntroDialog();
        
        setupHistoryDrawer();
        historyManager.clearCurrentActiveHistory();
        clearCurrentConversation();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reinitializeLLMIfNeeded();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveCurrentChat();
        releaseLLMResources();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Ensure services are unbound and cleaned up
        try {
            cleanup();
        } catch (Exception e) {
            Log.e(TAG, "Error during cleanup", e);
        }
    }

    private void initializeViews() {
        // First inflate the binding but don't set it as content view yet
        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        
        // Ensure input container and toolbar are fully opaque
        binding.inputContainer.setAlpha(1.0f);
        binding.toolbar.setAlpha(1.0f);
        binding.toolbar.setBackgroundColor(getResources().getColor(R.color.background, getTheme()));
        
        // Set input container background to be fully opaque
        binding.inputContainer.setBackgroundResource(R.drawable.bg_input_container);
        binding.inputContainer.setElevation(4f);  // Add elevation to ensure it's above other elements
        
        // Ensure the main content is fully opaque
        binding.mainContent.setAlpha(1.0f);
        binding.mainContent.setBackgroundColor(getResources().getColor(R.color.background, getTheme()));
        
        // Initialize handlers first
        initializeHandlers();
        
        // Then initialize UI components
        initializeChat();
        setupButtons();
        setupInputHandling();
        setupTitleTapCounter();
        
        // Initially disable all interactive components
        updateInteractionState();
    }

    private void initializeHandlers() {
        mediaHandler = new ChatMediaHandler(this);
        uiHandler = new ChatUIStateHandler(binding);
        conversationManager = new ConversationManager();
        historyManager = new ChatHistoryManager(this);
    }

    private void initializeChat() {
        chatAdapter = new ChatMessageAdapter();
        chatAdapter.setSpeakerClickListener(this);
        // Add copy message listener
        chatAdapter.setOnMessageLongClickListener((message, position) -> {
            showMessageOptions(message);
            return true;
        });
        UiUtils.setupChatRecyclerView(binding.recyclerView, chatAdapter);
        
        // Set initial watermark visibility
        updateWatermarkVisibility();
    }

    private void updateWatermarkVisibility() {
        if (binding.watermarkContainer != null) {
            binding.watermarkContainer.setVisibility(
                chatAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE
            );
        }
    }

    private void setupButtons() {
        setupNavigationButton();
        setupAttachmentButton();
        setupVoiceButton();
        setupSendButton();
        setupNewConversationButton();
    }

    private void setupNavigationButton() {
        View.OnClickListener historyClickListener = v -> {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START);
            } else {
                drawerLayout.openDrawer(GravityCompat.START);
            }
        };
        binding.historyButton.setOnClickListener(historyClickListener);
        // Ensure button is initially enabled and clickable
        binding.historyButton.setEnabled(true);
        binding.historyButton.setClickable(true);
    }

    private void setupAttachmentButton() {
        View.OnClickListener attachClickListener = v -> showAttachmentOptions();
        binding.attachButton.setOnClickListener(attachClickListener);
        binding.attachButtonExpanded.setOnClickListener(attachClickListener);
    }

    private void setupVoiceButton() {
        View.OnClickListener voiceClickListener = v -> toggleRecording();
        binding.voiceButton.setOnClickListener(voiceClickListener);
        binding.voiceButtonExpanded.setOnClickListener(voiceClickListener);

        View.OnLongClickListener voiceLongClickListener = v -> {
            showAudioList();
            return true;
        };
        binding.voiceButton.setOnLongClickListener(voiceLongClickListener);
        binding.voiceButtonExpanded.setOnLongClickListener(voiceLongClickListener);

        setupRecordingControls();
    }

    private void setupRecordingControls() {
        binding.recordingInput.cancelRecordingButton.setOnClickListener(v -> {
            stopRecording(false);
        });

        binding.recordingInput.finishRecordingButton.setOnClickListener(v -> {
            stopRecording(true);
        });
    }

    private void setupSendButton() {
        View.OnClickListener sendClickListener = v -> handleSendAction();
        binding.sendButton.setOnClickListener(sendClickListener);
        binding.sendButtonExpanded.setOnClickListener(sendClickListener);

        // Set initial send icon
        binding.sendButton.setBackgroundResource(R.drawable.bg_send_button);
        binding.sendButtonExpanded.setBackgroundResource(R.drawable.bg_send_button);
        
        // Always use send icon when AUDIO_CHAT_ENABLED is false
        if (!AppConstants.AUDIO_CHAT_ENABLED) {
            binding.sendButton.setImageResource(R.drawable.ic_send);
            binding.sendButtonExpanded.setImageResource(R.drawable.ic_send);
            // Hide voice buttons when audio chat is disabled
            binding.voiceButton.setVisibility(View.GONE);
            binding.voiceButtonExpanded.setVisibility(View.GONE);
        } else {
            binding.sendButton.setImageResource(R.drawable.ic_audio_wave);
            binding.sendButtonExpanded.setImageResource(R.drawable.ic_audio_wave);
            // Show voice buttons when audio chat is enabled
            binding.voiceButton.setVisibility(View.VISIBLE);
            binding.voiceButtonExpanded.setVisibility(View.VISIBLE);
        }

        // Initial button state (only if uiHandler is initialized)
        if (uiHandler != null) {
            updateSendButtonState();
        }
    }

    private void setupInputHandling() {
        TextWatcher textWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                updateSendButtonState();
            }
        };
        binding.messageInput.addTextChangedListener(textWatcher);
        binding.messageInputExpanded.addTextChangedListener(textWatcher);
    }

    private void updateSendButtonState() {
        if (uiHandler == null) return;

        boolean hasText = !uiHandler.getCurrentInputText().trim().isEmpty();
        boolean hasImage = uiHandler.getPendingImageUri() != null;
        boolean shouldEnable = hasText || hasImage || AppConstants.AUDIO_CHAT_ENABLED;

        // Update button state
        binding.sendButton.setEnabled(shouldEnable);
        binding.sendButtonExpanded.setEnabled(shouldEnable);
        
        // Update button appearance
        float alpha = shouldEnable ? ENABLED_ALPHA : DISABLED_ALPHA;
        binding.sendButton.setAlpha(alpha);
        binding.sendButtonExpanded.setAlpha(alpha);

        // Update icon based on content
        if (!AppConstants.AUDIO_CHAT_ENABLED || hasText || hasImage) {
            binding.sendButton.setImageResource(R.drawable.ic_send);
            binding.sendButtonExpanded.setImageResource(R.drawable.ic_send);
        } else {
            binding.sendButton.setImageResource(R.drawable.ic_audio_wave);
            binding.sendButtonExpanded.setImageResource(R.drawable.ic_audio_wave);
        }
    }

    private void setupNewConversationButton() {
        View.OnClickListener newConversationClickListener = v -> {
            // Save current chat if needed
            saveCurrentChat();
            // Clear current conversation
            clearCurrentConversation();
            // Clear active history
            historyManager.clearCurrentActiveHistory();
            // Refresh history list to show the newly saved chat
            refreshHistoryList();
        };
        binding.newConversationButton.setOnClickListener(newConversationClickListener);
        // Ensure button is initially enabled and clickable
        binding.newConversationButton.setEnabled(true);
        binding.newConversationButton.setClickable(true);
    }

    private void startInitialization() {
        synchronized (initLock) {
            if (isInitializing) return;
            isInitializing = true;
        }
        
        // Update UI state immediately when initialization starts
        updateInteractionState();
        
        // Remove overlay animation code and directly start services
        initializeServices();
    }

    private void initializeServices() {
        // Create a single background thread instead of thread pool to reduce memory usage
        Thread initThread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            
            try {
                Log.d(TAG, "Starting service initialization...");
                Log.d(TAG, "TTS_ENABLED: " + AppConstants.TTS_ENABLED);
                Log.d(TAG, "ASR_ENABLED: " + AppConstants.ASR_ENABLED);
                Log.d(TAG, "LLM_ENABLED: " + AppConstants.LLM_ENABLED);
                Log.d(TAG, "VLM_ENABLED: " + AppConstants.VLM_ENABLED);
                
                // Initialize LLM service if enabled
                if (AppConstants.LLM_ENABLED) {
                    initializeLLMService();
                }
                
                // Initialize VLM service if enabled
                if (AppConstants.VLM_ENABLED) {
                    initializeVLMService();
                }
                
                // Initialize TTS service if enabled (moved before ASR check since it doesn't require permissions)
                if (AppConstants.TTS_ENABLED) {
                    Log.d(TAG, "Initializing TTS service...");
                    try {
                        initializeTTSService();
                        Log.d(TAG, "TTS service initialization completed");
                    } catch (Exception e) {
                        Log.e(TAG, "Error initializing TTS service", e);
                    }
                }
                
                // Initialize ASR if enabled and audio permission is granted
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    if (AppConstants.ASR_ENABLED) {
                        initializeASRService();
                    }
                } else {
                    Log.w(TAG, "Audio permission not granted, skipping ASR initialization");
                }
                
                // Update UI on main thread
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isFinishing()) {
                        // Mark initialization as complete
                        synchronized (initLock) {
                            isInitializing = false;
                        }
                        updateInteractionState();
                        
                        // Log final service states
                        Log.d(TAG, "Service initialization complete. States:");
                        Log.d(TAG, "LLM ready: " + llmServiceReady);
                        Log.d(TAG, "VLM ready: " + vlmServiceReady);
                        Log.d(TAG, "ASR ready: " + asrServiceReady);
                        Log.d(TAG, "TTS ready: " + ttsServiceReady);
                    }
                });
                
            } catch (Exception e) {
                Log.e(TAG, "Error during service initialization", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isFinishing()) {
                        Toast.makeText(ChatActivity.this,
                                ChatActivity.this.getString(R.string.error_initializing_services) + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                        // Mark initialization as complete even on error
                        synchronized (initLock) {
                            isInitializing = false;
                        }
                        updateInteractionState();
                    }
                });
            }
        });
        initThread.start();
    }

    private void initializeLLMService() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);
        
        // Prepare LLM intent
        Intent llmIntent = new Intent(this, LLMEngineService.class);
        llmIntent.putExtra("model_path", AppConstants.MODEL_PATH);
        String preferredBackend = ModelUtils.getPreferredBackend();
        llmIntent.putExtra("preferred_backend", preferredBackend);
        
        // Show status on main thread
        new Handler(Looper.getMainLooper()).post(() -> {
            if (!isFinishing()) {
                Toast.makeText(ChatActivity.this,
                        ChatActivity.this.getString(R.string.initializing_model_with) + preferredBackend.toUpperCase() + " backend...",
                    Toast.LENGTH_SHORT).show();
            }
        });
        
        // Bind service on main thread
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                startService(llmIntent);
                if (bindService(llmIntent, llmConnection, Context.BIND_AUTO_CREATE)) {
                    success.set(true);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error binding LLM service", e);
            } finally {
                latch.countDown();
            }
        });
        
        // Wait with timeout
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new TimeoutException("LLM service binding timed out");
        }
        
        if (!success.get()) {
            throw new Exception("LLM service binding failed");
        }
        
        // Add delay to allow service to start
        Thread.sleep(1000);
    }

    private void initializeVLMService() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                bindService(new Intent(this, VLMEngineService.class),
                    vlmConnection, Context.BIND_AUTO_CREATE);
            } finally {
                latch.countDown();
            }
        });
        
        latch.await(5, TimeUnit.SECONDS);
    }

    private void initializeASRService() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Log.d(TAG, "Starting ASR service initialization...");
        
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Intent asrIntent = new Intent(this, ASREngineService.class);
                startService(asrIntent);
                if (!bindService(asrIntent, asrConnection, Context.BIND_AUTO_CREATE)) {
                    Log.e(TAG, "Failed to bind ASR service");
                    asrServiceReady = false;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error binding ASR service", e);
                asrServiceReady = false;
            } finally {
                latch.countDown();
            }
        });
        
        if (!latch.await(5, TimeUnit.SECONDS)) {
            Log.e(TAG, "ASR service initialization timed out");
            asrServiceReady = false;
            throw new TimeoutException("ASR service initialization timed out");
        }
    }

    private void initializeTTSService() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);
        
        Log.d(TAG, "Starting TTS service initialization...");
        
        // Prepare TTS intent
        Intent ttsIntent = new Intent(this, TTSEngineService.class);
        
        // Bind service on main thread
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                startService(ttsIntent);
                if (bindService(ttsIntent, ttsConnection, Context.BIND_AUTO_CREATE)) {
                    success.set(true);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error binding TTS service", e);
            } finally {
                latch.countDown();
            }
        });
        
        // Wait with timeout
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new TimeoutException("TTS service binding timed out");
        }
        
        if (!success.get()) {
            throw new Exception("TTS service binding failed");
        }
        
        // Add delay to allow service to start
        Thread.sleep(500);
    }

    private void handleSendAction() {
        if (uiHandler == null) return;

        String message = uiHandler.getCurrentInputText();
        Uri pendingImage = uiHandler.getPendingImageUri();

        if (!message.isEmpty() || pendingImage != null) {
            if (pendingImage != null) {
                handleImageMessage(pendingImage, message);
            } else {
                handleTextMessage(message);
            }
            uiHandler.clearInput();
        } else if (AppConstants.AUDIO_CHAT_ENABLED && AppConstants.ASR_ENABLED) {
            // Only start audio chat if both AUDIO_CHAT_ENABLED and ASR_ENABLED are true
            startAudioChat();
        } else if (AppConstants.AUDIO_CHAT_ENABLED) {
            // Show a message if audio chat is enabled but ASR is disabled
            Toast.makeText(this, this.getString( R.string.speech_recognition_is_disabled), Toast.LENGTH_SHORT).show();
        }
    }

    private void handleTextMessage(String message) {
        if (message.trim().isEmpty()) return;
        
        // Hide keyboard and clear input first
        hideKeyboard();
        uiHandler.clearInput();
        
        // Add user message to conversation
        ChatMessage userMessage = new ChatMessage(message, true);
        userMessage.setPromptId(promptId);
        conversationManager.addMessage(userMessage);
        chatAdapter.addMessage(userMessage);
        
        // Hide watermark when conversation starts
        updateWatermarkVisibility();
        
        // Create empty AI message with loading indicator
        ChatMessage aiMessage = new ChatMessage(getString(R.string.thinking), false);
        aiMessage.setPromptId(promptId);
        chatAdapter.addMessage(aiMessage);
        
        // Save chat after adding both messages
        saveCurrentChat();
        
        // Generate AI response with formatted prompt
        if (llmService != null) {
            String formattedPrompt = getFormattedPrompt(message);
            
            // Set UI to generation state BEFORE starting generation
            setSendButtonsAsStop(true);
            
            hasReceivedResponse = false;  // Reset at start of generation
            llmService.generateStreamingResponse(formattedPrompt, new LLMEngineService.StreamingResponseCallback() {
                private final StringBuilder currentResponse = new StringBuilder();
                private boolean isGenerating = true;  // Track generation state

                @Override
                public void onToken(String token) {
                    if (!isGenerating || token == null || token.isEmpty()) {
                        return;
                    }

                    if (!hasReceivedResponse) {
                        hasReceivedResponse = true;
                        conversationManager.addMessage(aiMessage);
                    }

                    runOnUiThread(() -> {
                        currentResponse.append(token);
                        aiMessage.updateText(currentResponse.toString());
                        chatAdapter.notifyItemChanged(chatAdapter.getItemCount() - 1);
                        UiUtils.scrollToLatestMessage(binding.recyclerView, chatAdapter.getItemCount(), false);
                    });
                }
            }).thenAccept(finalResponse -> {
                runOnUiThread(() -> {
                    if (finalResponse != null && !finalResponse.equals(AppConstants.LLM_DEFAULT_ERROR_RESPONSE)) {
                        String response = finalResponse.trim();
                        if (response.isEmpty()) {
                            // If we never received any valid tokens (still showing "thinking...")
                            if (!hasReceivedResponse) {
                                aiMessage.updateText(AppConstants.LLM_INVALID_TOKEN_ERROR);
                            } else if (!aiMessage.hasContent()) {
                                aiMessage.updateText(AppConstants.LLM_EMPTY_RESPONSE_ERROR);
                            }
                        } else if (!response.isEmpty()) {
                            aiMessage.updateText(finalResponse);
                        }
                        promptId++;
                    } else {
                        if (!aiMessage.hasContent()) {
                            aiMessage.updateText(AppConstants.LLM_DEFAULT_ERROR_RESPONSE);
                        }
                    }
                    
                    chatAdapter.notifyItemChanged(chatAdapter.getItemCount() - 1);
                    UiUtils.scrollToLatestMessage(binding.recyclerView, chatAdapter.getItemCount(), true);
                    
                    // Re-enable input and restore send button AFTER response is complete
                    setSendButtonsAsStop(false);
                    
                    saveCurrentChat();
                    refreshHistoryList();
                });
            }).exceptionally(throwable -> {
                Log.e(TAG, "Error generating response", throwable);
                runOnUiThread(() -> {
                    if (!aiMessage.hasContent()) {
                        aiMessage.updateText("Error: Unable to generate response. Please try again later.");
                    }
                    chatAdapter.notifyItemChanged(chatAdapter.getItemCount() - 1);
                    Toast.makeText(ChatActivity.this, ChatActivity.this.getString(R.string.error_generating_response), Toast.LENGTH_SHORT).show();
                    
                    setSendButtonsAsStop(false);
                });
                return null;
            });
        }
    }

    private void setSendButtonsAsStop(boolean isStop) {
        runOnUiThread(() -> {
            try {
                // Update UI state first
                uiHandler.setGeneratingState(isStop);
                
                // Disable input section
                binding.messageInput.setEnabled(!isStop);
                binding.messageInputExpanded.setEnabled(!isStop);
                binding.messageInput.setAlpha(isStop ? DISABLED_ALPHA : ENABLED_ALPHA);
                binding.messageInputExpanded.setAlpha(isStop ? DISABLED_ALPHA : ENABLED_ALPHA);
                
                // Update hint text
                String hintText = isStop ? this.getString(R.string.please_wait_for_the_response_to_finish) : this.getString(R.string.type_a_message);
                binding.messageInput.setHint(hintText);
                binding.messageInputExpanded.setHint(hintText);
                
                // Clear text when disabling
                if (isStop) {
                    binding.messageInput.setText("");
                    binding.messageInputExpanded.setText("");
                }

                // Configure send/stop buttons - AFTER UI state update
                if (isStop) {
                    // When in stop mode, ensure button is fully visible and enabled
                    binding.sendButton.setImageResource(R.drawable.ic_stop);
                    binding.sendButtonExpanded.setImageResource(R.drawable.ic_stop);
                    binding.sendButton.setEnabled(true);
                    binding.sendButtonExpanded.setEnabled(true);
                    binding.sendButton.setAlpha(1.0f);  // Use full opacity for stop button
                    binding.sendButtonExpanded.setAlpha(1.0f);
                    
                    // Set click listener for stop functionality
                    View.OnClickListener stopListener = v -> {
                        if (llmService != null) {
                            // Show stopping feedback
                            Toast.makeText(ChatActivity.this, ChatActivity.this.getString(R.string.stopping_generation), Toast.LENGTH_SHORT).show();
                            
                            // Stop generation and wait for completion
                            llmService.stopGeneration();
                            
                            // Wait for a short delay to ensure generation has stopped
                            new Handler().postDelayed(() -> {
                                // Reset UI state after delay
                                setSendButtonsAsStop(false);
                                
                                // Keep button enabled but update appearance
                                binding.sendButton.setImageResource(R.drawable.ic_send);
                                binding.sendButtonExpanded.setImageResource(R.drawable.ic_send);
                            }, 1000); // 1 second delay to ensure generation has stopped
                        }
                    };
                    binding.sendButton.setOnClickListener(stopListener);
                    binding.sendButtonExpanded.setOnClickListener(stopListener);
                    
                    // Force button state update
                    binding.sendButton.post(() -> {
                        binding.sendButton.setEnabled(true);
                        binding.sendButton.setAlpha(1.0f);
                    });
                    binding.sendButtonExpanded.post(() -> {
                        binding.sendButtonExpanded.setEnabled(true);
                        binding.sendButtonExpanded.setAlpha(1.0f);
                    });
                } else {
                    // Normal send mode
                    binding.sendButton.setImageResource(R.drawable.ic_send);
                    binding.sendButtonExpanded.setImageResource(R.drawable.ic_send);
                    binding.sendButton.setEnabled(true);
                    binding.sendButtonExpanded.setEnabled(true);
                    binding.sendButton.setAlpha(ENABLED_ALPHA);
                    binding.sendButtonExpanded.setAlpha(ENABLED_ALPHA);
                    
                    // Reset to send functionality
                    View.OnClickListener sendListener = v -> handleSendAction();
                    binding.sendButton.setOnClickListener(sendListener);
                    binding.sendButtonExpanded.setOnClickListener(sendListener);
                }
                
                // Force layout update
                binding.inputContainer.post(() -> {
                    binding.inputContainer.requestLayout();
                    binding.inputContainer.invalidate();
                });
                
            } catch (Exception e) {
                Log.e(TAG, "Error updating UI state", e);
            }
        });
    }

    private void handleImageMessage(Uri imageUri, String message) {
        ChatMessage userMessage = new ChatMessage(message, true);
        userMessage.setImageUri(imageUri);
        conversationManager.addMessage(userMessage);
        chatAdapter.addMessage(userMessage);
        
        if (vlmService != null) {
            vlmService.analyzeImage(imageUri, message)
                .thenAccept(response -> {
                    runOnUiThread(() -> {
                        ChatMessage aiMessage = new ChatMessage(response, false);
                        conversationManager.addMessage(aiMessage);
                        chatAdapter.addMessage(aiMessage);
                        UiUtils.scrollToLatestMessage(binding.recyclerView, chatAdapter.getItemCount(), true);
                    });
                })
                .exceptionally(throwable -> {
                    Log.e(TAG, "Error analyzing image", throwable);
                    runOnUiThread(() -> Toast.makeText(this, this.getString(R.string.error_analyzing_image), Toast.LENGTH_SHORT).show());
                    return null;
                });
        }
    }

    private void toggleRecording() {
        // Skip if ASR is disabled
        if (!AppConstants.ASR_ENABLED) {
            Toast.makeText(this, this.getString(R.string.speech_recognition_is_disabled), Toast.LENGTH_SHORT).show();
            return;
        }

        if (!hasAudioPermission()) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 
                PERMISSION_REQUEST_CODE);
            return;
        }

        if (mediaHandler.isRecording()) {
            stopRecording(true);
        } else {
            startRecording();
        }
    }

    private void startRecording() {
        if (asrService == null) {
            Toast.makeText(this, this.getString(R.string.ASR_service_not_ready), Toast.LENGTH_SHORT).show();
            return;
        }

        // Start ASR service first
        asrService.startListening(result -> {
            runOnUiThread(() -> {
                if (result.startsWith("Partial: ")) {
                    String partialText = result.substring(9);
                    binding.messageInput.setText(partialText);
                } else if (!result.startsWith("Error: ") && !result.equals("Ready for speech...")) {
                    binding.messageInput.setText(result);
                    binding.messageInputExpanded.setText(result);
                    uiHandler.updateSendButtonState();
                    stopRecording(false);
                } else if (result.startsWith("Error: ")) {
                    Toast.makeText(this, result, Toast.LENGTH_SHORT).show();
                    stopRecording(false);
                }
            });
        });

        // Then start audio recording
        mediaHandler.startRecording();
        uiHandler.updateRecordingState(true);
    }

    private void stopRecording(boolean shouldSave) {
        if (asrService != null) {
            asrService.stopListening();
        }
        mediaHandler.stopRecording(shouldSave);
        uiHandler.updateRecordingState(false);
    }

    private void showAttachmentOptions() {
        PopupMenu popup = new PopupMenu(this, 
            binding.expandedInput.getVisibility() == View.VISIBLE ? 
            binding.attachButtonExpanded : binding.attachButton);

        popup.getMenu().add(0, 1, 0, "Attach Photos");
        popup.getMenu().add(0, 2, 0, "Take Photo");
        popup.getMenu().add(0, 3, 0, "Attach Files");

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    startActivityForResult(mediaHandler.createImageSelectionIntent(), PICK_IMAGE_REQUEST);
                    break;
                case 2:
                    handleCameraCapture();
                    break;
                case 3:
                    startActivityForResult(mediaHandler.createFileSelectionIntent(), PICK_FILE_REQUEST);
                    break;
            }
            return true;
        });

        popup.show();
    }

    private void handleCameraCapture() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, PERMISSION_REQUEST_CODE);
            return;
        }

        try {
            Intent intent = mediaHandler.createCameraCaptureIntent();
            if (intent != null) {
                startActivityForResult(intent, CAPTURE_IMAGE_REQUEST);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error creating camera intent", e);
            Toast.makeText(this, this.getString(R.string.error_launching_camera), Toast.LENGTH_SHORT).show();
        }
    }

    private void showAudioList() {
        File[] files = FileUtils.getAudioRecordings(this);
        if (files == null || files.length == 0) {
            Toast.makeText(this, this.getString(R.string.no_recordings_found), Toast.LENGTH_SHORT).show();
            return;
        }

        Arrays.sort(files, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
        List<File> audioFiles = new ArrayList<>(Arrays.asList(files));

        UiUtils.showAudioListDialog(this, audioFiles, new AudioListAdapter.OnAudioActionListener() {
            @Override
            public void onReplayClick(File file) {
                // TODO: Implement audio playback
            }

            @Override
            public void onDeleteClick(File file) {
                if (file.delete()) {
                    Toast.makeText(ChatActivity.this, ChatActivity.this.getString(R.string.recording_deleted), Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void navigateToHome() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    private void startAudioChat() {
        Intent intent = new Intent(this, AudioChatActivity.class);
        startActivity(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;

        switch (requestCode) {
            case PICK_IMAGE_REQUEST:
                if (data != null && data.getData() != null) {
                    uiHandler.setImagePreview(data.getData());
                }
                break;
            case CAPTURE_IMAGE_REQUEST:
                if (mediaHandler.getCurrentPhotoPath() != null) {
                    uiHandler.setImagePreview(Uri.fromFile(new File(mediaHandler.getCurrentPhotoPath())));
                }
                break;
            case PICK_FILE_REQUEST:
                if (data != null && data.getData() != null) {
                    ChatMessage fileMessage = mediaHandler.handleSelectedFile(data.getData());
                    if (fileMessage != null) {
                        chatAdapter.addMessage(fileMessage);
                    }
                }
                break;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.length > 0) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (permissions[0].equals(android.Manifest.permission.CAMERA)) {
                    handleCameraCapture();
                } else if (permissions[0].equals(android.Manifest.permission.RECORD_AUDIO)) {
                    // Only start initialization if ASR is enabled
                    if (AppConstants.ASR_ENABLED) {
                        startInitialization();
                    }
                }
            } else {
                String message = permissions[0].equals(android.Manifest.permission.CAMERA) ?
                        getString(R.string.camera_permission_required) :
                        getString(R.string.mic_permission_required);
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onSpeakerClick(String messageText) {
        if (ttsService == null) {
            Toast.makeText(this, this.getString(R.string.text_to_speech_service_not_available), Toast.LENGTH_SHORT).show();
            return;
        }

        if (!ttsService.isReady()) {
            Toast.makeText(this, this.getString(R.string.text_to_speech_is_still_initializing), Toast.LENGTH_SHORT).show();
            return;
        }

        // Show loading state
        Toast.makeText(this, this.getString(R.string.converting_text_to_speech), Toast.LENGTH_SHORT).show();

        // Run TTS in background
        CompletableFuture.runAsync(() -> {
            try {
                ttsService.speak(messageText)
                    .thenRun(() -> {
                        // Success - no need for notification
                    })
                    .exceptionally(throwable -> {
                        Log.e(TAG, "Error converting text to speech", throwable);
                        runOnUiThread(() -> Toast.makeText(this, 
                            this.getString(R.string.error_playing_audio), Toast.LENGTH_SHORT).show());
                        return null;
                    });
            } catch (Exception e) {
                Log.e(TAG, "Error initiating text to speech", e);
                runOnUiThread(() -> Toast.makeText(this, 
                    this.getString(R.string.error_starting_audio_playback), Toast.LENGTH_SHORT).show());
            }
        }).exceptionally(throwable -> {
            Log.e(TAG, "Error in TTS background task", throwable);
            return null;
        });
    }

    private void cleanup() {
        // Run cleanup in background to prevent ANR
        CompletableFuture.runAsync(() -> {
            try {
                // Save current chat before cleanup
                saveCurrentChat();
                
                // Unbind services with timeout
                ExecutorService cleanupExecutor = Executors.newSingleThreadExecutor();
                Future<?> cleanupFuture = cleanupExecutor.submit(() -> {
                    unbindAllServices();
                });
                
                try {
                    cleanupFuture.get(5, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    Log.w(TAG, "Service unbinding timed out", e);
                    cleanupFuture.cancel(true);
                }
                
                cleanupExecutor.shutdownNow();
                
                // Release other resources
                mediaHandler.release();
                binding = null;
                conversationManager = null;
                historyManager = null;
                historyAdapter = null;
                drawerLayout = null;
                
                // Force garbage collection
                System.gc();
                
            } catch (Exception e) {
                Log.e(TAG, "Error during cleanup", e);
            }
        });
    }

    private void unbindAllServices() {
        if (llmService != null) {
            llmService.releaseResources();
            unbindService(llmConnection);
        }
        if (vlmService != null) unbindService(vlmConnection);
        if (asrService != null) unbindService(asrConnection);
        if (ttsService != null) unbindService(ttsConnection);
    }

    private void releaseLLMResources() {
        if (llmService != null) {
            llmService.releaseResources();
        }
    }

    private void reinitializeLLMIfNeeded() {
        if (llmService != null && !llmService.isReady()) {
            llmService.initialize()
                .thenAccept(success -> {
                    if (!success) {
                        Log.e(TAG, "Failed to reinitialize LLM");
                        runOnUiThread(() -> Toast.makeText(this, this.getString(R.string.failed_to_reinitialize_llm), Toast.LENGTH_SHORT).show());
                    }
                });
        }
    }

    // Service Connections
    private final ServiceConnection llmConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            llmService = ((LLMEngineService.LocalBinder) service).getService();
            if (llmService != null) {
                Toast.makeText(ChatActivity.this, ChatActivity.this.getString(R.string.initializing_model) , Toast.LENGTH_SHORT).show();
                
                llmService.initialize().thenAccept(success -> {
                    llmServiceReady = success;
                    if (success) {
                        runOnUiThread(() -> {
                            String modelName = llmService.getModelName();
                            String backend = llmService.getCurrentBackend();
                            if (modelName != null && !modelName.isEmpty()) {
                                String displayText = String.format("%s (%s)", modelName, ModelUtils.getBackendDisplayName(backend));
                                binding.modelNameText.setText(displayText);
                                binding.modelNameText.setTextColor(getResources().getColor(R.color.text_primary, getTheme()));
                            } else {
                                binding.modelNameText.setText( ChatActivity.this.getString(R.string.unknown_model) );
                            }
                            updateInteractionState();
                        });
                    } else {
                        runOnUiThread(() -> {
                            binding.modelNameText.setText(ChatActivity.this.getString(R.string.model_error));
                            binding.modelNameText.setTextColor(getResources().getColor(R.color.error, getTheme()));
                            Toast.makeText(ChatActivity.this, ChatActivity.this.getString(R.string.failed_to_initialize_model), Toast.LENGTH_SHORT).show();
                            updateInteractionState();
                        });
                    }
                }).exceptionally(throwable -> {
                    Log.e(TAG, "Error initializing model", throwable);
                    llmServiceReady = false;
                    runOnUiThread(() -> {
                        binding.modelNameText.setText(ChatActivity.this.getString(R.string.model_error));
                        binding.modelNameText.setTextColor(getResources().getColor(R.color.error, getTheme()));
                        Toast.makeText(ChatActivity.this, ChatActivity.this.getString(R.string.error_initializing_model), Toast.LENGTH_SHORT).show();
                        updateInteractionState();
                    });
                    return null;
                });
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            llmService = null;
            llmServiceReady = false;
            runOnUiThread(() -> {
                binding.modelNameText.setText(ChatActivity.this.getString(R.string.model_disconnected));
                binding.modelNameText.setTextColor(getResources().getColor(R.color.error, getTheme()));
                Toast.makeText(ChatActivity.this, ChatActivity.this.getString(R.string.model_service_disconnected) , Toast.LENGTH_SHORT).show();
                updateInteractionState();
            });
        }
    };

    private final ServiceConnection vlmConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            vlmService = ((VLMEngineService.LocalBinder) service).getService();
            vlmServiceReady = vlmService != null;
            updateInteractionState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            vlmService = null;
            vlmServiceReady = false;
            updateInteractionState();
        }
    };

    private final ServiceConnection asrConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "ASR service connected");
            asrService = ((ASREngineService.LocalBinder) service).getService();
            if (asrService != null) {
                asrService.initialize().thenAccept(success -> {
                    asrServiceReady = success;
                    Log.d(TAG, "ASR initialization " + (success ? "successful" : "failed"));
                    if (!success) {
                        runOnUiThread(() -> Toast.makeText(ChatActivity.this,
                                ChatActivity.this.getString(R.string.ars_initialization_failed), Toast.LENGTH_SHORT ).show());
                    }
                    updateInteractionState();
                }).exceptionally(throwable -> {
                    Log.e(TAG, "Error initializing ASR", throwable);
                    asrServiceReady = false;
                    runOnUiThread(() -> Toast.makeText(ChatActivity.this,
                            ChatActivity.this.getString(R.string.ars_initialization_failed) + throwable.getMessage(),
                        Toast.LENGTH_SHORT).show());
                    updateInteractionState();
                    return null;
                });
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "ASR service disconnected");
            asrService = null;
            asrServiceReady = false;
            updateInteractionState();
        }
    };

    private final ServiceConnection ttsConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "TTS service connected");
            ttsService = ((TTSEngineService.LocalBinder) service).getService();
            if (ttsService != null) {
                runOnUiThread(() -> Toast.makeText(ChatActivity.this,
                        ChatActivity.this.getString(R.string.initializing_text_to_speech), Toast.LENGTH_SHORT).show());
                
                CompletableFuture.runAsync(() -> {
                    try {
                        ttsService.initialize()
                            .thenAccept(success -> {
                                ttsServiceReady = success;
                                Log.d(TAG, "TTS initialization " + (success ? "successful" : "failed"));
                                if (success) {
                                    runOnUiThread(() -> Toast.makeText(ChatActivity.this,
                                            ChatActivity.this.getString(R.string.text_to_speech_ready), Toast.LENGTH_SHORT).show());
                                } else {
                                    runOnUiThread(() -> Toast.makeText(ChatActivity.this,
                                            ChatActivity.this.getString(R.string.failed_to_initialize_text_to_speech), Toast.LENGTH_SHORT).show());
                                }
                                updateInteractionState();
                            })
                            .exceptionally(throwable -> {
                                Log.e(TAG, "Error initializing TTS", throwable);
                                ttsServiceReady = false;
                                runOnUiThread(() -> {
                                    Toast.makeText(ChatActivity.this,
                                            ChatActivity.this.getString(R.string.error_initialize_text_to_speech) + throwable.getMessage(),
                                        Toast.LENGTH_SHORT).show();
                                    updateInteractionState();
                                });
                                return null;
                            });
                    } catch (Exception e) {
                        Log.e(TAG, "Error starting TTS initialization", e);
                        ttsServiceReady = false;
                        updateInteractionState();
                    }
                });
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "TTS service disconnected");
            ttsService = null;
            ttsServiceReady = false;
            updateInteractionState();
        }
    };

    // Add this new method to handle keyboard hiding
    private void hideKeyboard() {
        View view = getCurrentFocus();
        if (view != null) {
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private void setupHistoryDrawer() {
        drawerLayout = binding.drawerLayout;
        historyManager = new ChatHistoryManager(this);
        historyAdapter = new ChatHistoryAdapter();
        
        // Configure drawer to slide the main content
        drawerLayout.setScrimColor(Color.TRANSPARENT);
        drawerLayout.setDrawerElevation(0f);
        
        // Enable sliding content behavior
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
        
        // Set drawer listener for animation and dimming effect
        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            private final View mainContent = binding.getRoot().findViewById(R.id.mainContent);
            private final View contentOverlay = binding.getRoot().findViewById(R.id.contentOverlay);

            @Override
            public void onDrawerSlide(View drawerView, float slideOffset) {
                // Move the main content with the drawer
                mainContent.setTranslationX(drawerView.getWidth() * slideOffset);
                
                // Show and update overlay opacity
                if (slideOffset > 0) {
                    contentOverlay.setVisibility(View.VISIBLE);
                    contentOverlay.setAlpha(0.6f * slideOffset);
                } else {
                    contentOverlay.setVisibility(View.GONE);
                }
            }

            @Override
            public void onDrawerClosed(View drawerView) {
                contentOverlay.setVisibility(View.GONE);
                contentOverlay.setAlpha(0f);
                
                // Exit selection mode when drawer is closed
                if (historyAdapter.isSelectionMode() && historyAdapter.getSelectedHistories().isEmpty()) {
                    exitSelectionMode();
                }
            }

            @Override
            public void onDrawerStateChanged(int newState) {
                // Handle back press in selection mode
                if (newState == DrawerLayout.STATE_DRAGGING && historyAdapter.isSelectionMode()) {
                    if (historyAdapter.getSelectedHistories().isEmpty()) {
                        exitSelectionMode();
                    }
                }
            }
        });
        
        RecyclerView historyRecyclerView = findViewById(R.id.historyRecyclerView);
        historyRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        historyRecyclerView.setAdapter(historyAdapter);

        ImageButton deleteButton = findViewById(R.id.deleteHistoryButton);
        CheckBox selectAllCheckbox = findViewById(R.id.selectAllCheckbox);

        deleteButton.setOnClickListener(v -> {
            if (historyAdapter.isSelectionMode()) {
                // If in selection mode and has selections, show delete confirmation
                Set<String> selectedIds = historyAdapter.getSelectedHistories();
                if (!selectedIds.isEmpty()) {
                    showDeleteConfirmation();
                } else {
                    // Exit selection mode if no histories are selected
                    exitSelectionMode();
                }
            } else {
                // Enter selection mode
                historyAdapter.setSelectionMode(true);
                selectAllCheckbox.setVisibility(View.VISIBLE);
                deleteButton.setImageResource(R.drawable.ic_delete);
            }
        });

        // Update delete button appearance when selection changes
        historyAdapter.setOnSelectionChangeListener(selectedCount -> {
            if (selectedCount > 0) {
                deleteButton.setColorFilter(getResources().getColor(R.color.error, getTheme()));
            } else {
                deleteButton.clearColorFilter();
            }
        });

        selectAllCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            historyAdapter.selectAll(isChecked);
        });

        // Load and display chat histories
        refreshHistoryList();

        // Set click listener for history items
        historyAdapter.setOnHistoryClickListener(history -> {
            // First save the current conversation if it exists
            saveCurrentChat();
            
            // Clear the current conversation display
            clearCurrentConversation();
            
            // Load the selected chat history
            for (ChatMessage message : history.getMessages()) {
                conversationManager.addMessage(message);
                chatAdapter.addMessage(message);
            }
            
            // Set this as the current active history
            historyManager.setCurrentActiveHistory(history);
            drawerLayout.closeDrawers();
            updateWatermarkVisibility();
        });
    }

    private void showDeleteConfirmation() {
        Set<String> selectedIds = historyAdapter.getSelectedHistories();
        if (selectedIds.isEmpty()) {
            Toast.makeText(this, this.getString(R.string.no_histories_selected), Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle(this.getString(R.string.delete_selected_histories))
            .setMessage(this.getString(R.string.check_delete, selectedIds.size() ) )
            .setPositiveButton(this.getString(R.string.delete), (dialog, which) -> {
                // Delete selected histories
                for (String id : selectedIds) {
                    historyManager.deleteHistory(id);
                    // If the deleted history was the current active one, clear the conversation
                    ChatHistory currentHistory = historyManager.getCurrentActiveHistory();
                    if (currentHistory != null && currentHistory.getId().equals(id)) {
                        historyManager.clearCurrentActiveHistory();
                        clearCurrentConversation();
                    }
                }
                
                // Exit selection mode and refresh the list
                exitSelectionMode();
                refreshHistoryList();
                Toast.makeText(this, selectedIds.size() > 1 ? 
                     this.getString(R.string.selected_histories_deleted) : this.getString(R.string.history_deleted), Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(this.getString(R.string.cancel), null)
            .show();
    }

    private void exitSelectionMode() {
        historyAdapter.setSelectionMode(false);
        findViewById(R.id.selectAllCheckbox).setVisibility(View.GONE);
        ImageButton deleteButton = findViewById(R.id.deleteHistoryButton);
        deleteButton.setImageResource(R.drawable.ic_delete);
        deleteButton.clearColorFilter();  // Remove the tint
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START) && historyAdapter.isSelectionMode()) {
            exitSelectionMode();
            return;
        }
        super.onBackPressed();
    }

    private void refreshHistoryList() {
        List<ChatHistory> histories = historyManager.loadAllHistories();
        historyAdapter.setHistories(histories);
    }

    private void saveCurrentChat() {
        List<ChatMessage> messages = conversationManager.getMessages();
        if (!messages.isEmpty()) {
            // Use first message as title, or a default title if it's empty
            String title = messages.get(0).getText();
            if (title.isEmpty() || title.length() > 50) {
                title = "Chat " + new SimpleDateFormat("yyyy-MM-dd HH:mm", 
                    Locale.getDefault()).format(new Date());
            }
            
            // Create or update history
            historyManager.createNewHistory(title, messages);
        }
    }

    private void clearCurrentConversation() {
        // Clear the conversation manager
        conversationManager.clearMessages();
        // Clear the chat adapter
        chatAdapter.clearMessages();
        // Update watermark visibility
        updateWatermarkVisibility();
    }

    private String getFormattedPrompt(String userMessage) {
        // First try with full history
        List<ChatMessage> allMessages = conversationManager.getMessages();
        List<ChatMessage> historyMessages = new ArrayList<>();
        
        if (!allMessages.isEmpty()) {
            // Get messages up to but not including the last one (which would be the current query)
            int endIndex = allMessages.size() - 1;
            int startIndex = Math.max(0, endIndex - (AppConstants.CONVERSATION_HISTORY_LOOKBACK * 2));
            for (int i = startIndex; i < endIndex; i++) {
                historyMessages.add(allMessages.get(i));
            }
        }
        
        // First try formatting with history
        String fullPrompt = PromptManager.formatCompletePrompt(userMessage, historyMessages, ModelType.LLAMA_3_2);
        
        // Check if prompt might exceed max length (using conservative estimate)
        if (fullPrompt.length() > AppConstants.LLM_MAX_INPUT_LENGTH * 3) { // Assuming average of 3 chars per token
            Log.w(TAG, "Prompt too long with history, removing history to fit token limit");
            // Format prompt with empty history list to get just system prompt + user message
            String reducedPrompt = PromptManager.formatCompletePrompt(userMessage, new ArrayList<>(), ModelType.LLAMA_3_2);
            Log.d(TAG, "Reduced prompt without history: " + reducedPrompt);
            return reducedPrompt;
        }
        
        return fullPrompt;
    }

    private void setupTitleTapCounter() {
        binding.modelNameText.setOnClickListener(v -> {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastTapTime > TAP_TIMEOUT_MS) {
                titleTapCount = 0;
            }
            lastTapTime = currentTime;
            
            titleTapCount++;
            if (titleTapCount == TAPS_TO_SHOW_MAIN) {
                titleTapCount = 0;
                // Launch MainActivity
                Intent intent = new Intent(this, MainActivity.class);
                startActivity(intent);
            } else if (titleTapCount >= TAPS_TO_SHOW_MAIN - 2) {
                // Show feedback when close to activation
                int remaining = TAPS_TO_SHOW_MAIN - titleTapCount;
                String message = this.getString(R.string.more_taps, remaining, (remaining == 1 ? "" : "s"));
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showMessageOptions(ChatMessage message) {
        PopupMenu popup = new PopupMenu(this, binding.recyclerView);
        popup.getMenu().add(0, 1, 0, "Copy text");
        if (message.hasImage()) {
            popup.getMenu().add(0, 2, 0, "Save image");
        }

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    // Copy text to clipboard
                    android.content.ClipboardManager clipboard = 
                        (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    android.content.ClipData clip = 
                        android.content.ClipData.newPlainText("Message", message.getText());
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(this, this.getString(R.string.text_copied_to_clipboard), Toast.LENGTH_SHORT).show();
                    return true;
                case 2:
                    // Save image
                    if (message.hasImage()) {
                        saveImage(message.getImageUri());
                    }
                    return true;
            }
            return false;
        });

        popup.show();
    }

    private void saveImage(Uri imageUri) {
        try {
            // Create a copy of the image in the Pictures directory
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = "GAI_" + timestamp + ".jpg";
            
            // Get the content resolver
            android.content.ContentResolver resolver = getContentResolver();
            
            // Create image collection for API 29 and above
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, 
                android.os.Environment.DIRECTORY_PICTURES);

            // Insert the image
            Uri destUri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (destUri != null) {
                try (java.io.InputStream in = resolver.openInputStream(imageUri);
                     java.io.OutputStream out = resolver.openOutputStream(destUri)) {
                    if (in != null && out != null) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                        Toast.makeText(this, this.getString(R.string.image_saved_to_pictures), Toast.LENGTH_SHORT).show();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving image", e);
            Toast.makeText(this, this.getString(R.string.error_saving_image), Toast.LENGTH_SHORT).show();
        }
    }

    private void updateInteractionState() {
        // Check initialization state
        boolean isInitializationInProgress;
        synchronized (initLock) {
            isInitializationInProgress = isInitializing;
        }

        runOnUiThread(() -> {
            // Show/hide content overlay based on initialization state
            binding.contentOverlay.setVisibility(isInitializationInProgress ? View.VISIBLE : View.GONE);
            binding.contentOverlay.setAlpha(isInitializationInProgress ? 0.5f : 0f);

            if (isInitializationInProgress) {
                // During initialization, disable all interactive components
                disableAllInteractions();
                
                // Show initialization state in model name
                binding.modelNameText.setText(this.getString(R.string.initializing));
                binding.modelNameText.setTextColor(getResources().getColor(R.color.text_primary, getTheme()));
            } else {
                // After initialization, enable components based on service flags
                
                // Enable basic UI components
                enableBasicComponents();
                
                // Handle LLM-dependent components
                if (AppConstants.LLM_ENABLED) {
                    enableLLMComponents();
                } else {
                    disableLLMComponents();
                }
                
                // Handle VLM-dependent components
                if (AppConstants.VLM_ENABLED) {
                    enableVLMComponents();
                } else {
                    disableVLMComponents();
                }
                
                // Handle ASR-dependent components
                if (AppConstants.ASR_ENABLED) {
                    enableASRComponents();
                } else {
                    disableASRComponents();
                }
                
                // Handle TTS-dependent components
                if (AppConstants.TTS_ENABLED) {
                    enableTTSComponents();
                } else {
                    disableTTSComponents();
                }
                
                // Update model name and status
                updateModelNameStatus();
            }
            
            // Force a layout pass to ensure all changes are applied
            binding.inputContainer.requestLayout();
            binding.inputContainer.invalidate();
        });
    }

    private void disableAllInteractions() {
        binding.inputContainer.setEnabled(false);
        binding.collapsedInput.setEnabled(false);
        binding.expandedInput.setEnabled(false);
        
        binding.messageInput.setEnabled(false);
        binding.messageInput.setFocusable(false);
        binding.messageInput.setFocusableInTouchMode(false);
        binding.messageInput.setAlpha(DISABLED_ALPHA);
        
        binding.messageInputExpanded.setEnabled(false);
        binding.messageInputExpanded.setFocusable(false);
        binding.messageInputExpanded.setFocusableInTouchMode(false);
        binding.messageInputExpanded.setAlpha(DISABLED_ALPHA);
        
        binding.historyButton.setEnabled(false);
        binding.historyButton.setClickable(false);
        binding.historyButton.setAlpha(DISABLED_ALPHA);
        
        binding.newConversationButton.setEnabled(false);
        binding.newConversationButton.setClickable(false);
        binding.newConversationButton.setAlpha(DISABLED_ALPHA);
        
        binding.attachButton.setEnabled(false);
        binding.attachButton.setAlpha(DISABLED_ALPHA);
        binding.attachButtonExpanded.setEnabled(false);
        binding.attachButtonExpanded.setAlpha(DISABLED_ALPHA);
        
        binding.voiceButton.setEnabled(false);
        binding.voiceButton.setAlpha(DISABLED_ALPHA);
        binding.voiceButtonExpanded.setEnabled(false);
        binding.voiceButtonExpanded.setAlpha(DISABLED_ALPHA);
        
        binding.sendButton.setEnabled(false);
        binding.sendButton.setAlpha(DISABLED_ALPHA);
        binding.sendButtonExpanded.setEnabled(false);
        binding.sendButtonExpanded.setAlpha(DISABLED_ALPHA);
        
        RecyclerView historyRecyclerView = findViewById(R.id.historyRecyclerView);
        if (historyRecyclerView != null) {
            historyRecyclerView.setEnabled(false);
            historyRecyclerView.setAlpha(DISABLED_ALPHA);
        }
    }

    private void enableBasicComponents() {
        binding.inputContainer.setEnabled(true);
        binding.collapsedInput.setEnabled(true);
        binding.expandedInput.setEnabled(true);
        
        binding.historyButton.setEnabled(true);
        binding.historyButton.setClickable(true);
        binding.historyButton.setAlpha(ENABLED_ALPHA);
        
        binding.newConversationButton.setEnabled(true);
        binding.newConversationButton.setClickable(true);
        binding.newConversationButton.setAlpha(ENABLED_ALPHA);
        
        RecyclerView historyRecyclerView = findViewById(R.id.historyRecyclerView);
        if (historyRecyclerView != null) {
            historyRecyclerView.setEnabled(true);
            historyRecyclerView.setAlpha(ENABLED_ALPHA);
            if (historyAdapter != null) {
                historyAdapter.notifyDataSetChanged();
            }
        }
    }

    private void updateModelNameStatus() {
        if (llmService != null) {
            String modelName = llmService.getModelName();
            String backend = llmService.getCurrentBackend();
            if (modelName != null && !modelName.isEmpty()) {
                String displayText = String.format("%s (%s)", modelName, ModelUtils.getBackendDisplayName(backend));
                binding.modelNameText.setText(displayText);
                binding.modelNameText.setTextColor(getResources().getColor(R.color.text_primary, getTheme()));
            } else {
                binding.modelNameText.setText(this.getString(R.string.unknown_model));
            }
        } else {
            binding.modelNameText.setText(this.getString(R.string.model_not_available));
            binding.modelNameText.setTextColor(getResources().getColor(R.color.error, getTheme()));
        }
    }

    private boolean hasAudioPermission() {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void showIntroDialog() {
        Log.d(TAG, "Showing intro dialog");
        try {
            // Ensure we're on the main thread
            if (Looper.myLooper() != Looper.getMainLooper()) {
                new Handler(Looper.getMainLooper()).post(this::showIntroDialog);
                return;
            }

            // Check if activity is finishing
            if (isFinishing()) {
                Log.w(TAG, "Activity is finishing, skipping dialog");
                return;
            }

            IntroDialog dialog = new IntroDialog(this);
            
            // Set dialog window properties
            if (dialog.getWindow() != null) {
                // Set a semi-transparent dim background
                dialog.getWindow().setDimAmount(0.5f);
                // Set the background color
                dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    getResources().getColor(R.color.background, getTheme())
                ));
            }
            
            // Set custom dismiss listener to start initialization
            dialog.setOnFinalButtonClickListener(() -> {
                // Only request RECORD_AUDIO permission if ASR is enabled
                if (AppConstants.ASR_ENABLED && !hasAudioPermission()) {
                    requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 
                        PERMISSION_REQUEST_CODE);
                } else {
                    startInitialization();
                }
            });
            
            dialog.setOnDismissListener(dialogInterface -> {
                Log.d(TAG, "Intro dialog dismissed");
            });
            
            dialog.show();
            
        } catch (Exception e) {
            Log.e(TAG, "Error showing intro dialog", e);
        }
    }

    private void enableASRComponents() {
        if (hasAudioPermission()) {
            binding.voiceButton.setEnabled(true);
            binding.voiceButton.setVisibility(View.VISIBLE);
            binding.voiceButton.setAlpha(ENABLED_ALPHA);
            binding.voiceButtonExpanded.setEnabled(true);
            binding.voiceButtonExpanded.setVisibility(View.VISIBLE);
            binding.voiceButtonExpanded.setAlpha(ENABLED_ALPHA);
        }
    }

    private void disableASRComponents() {
        binding.voiceButton.setEnabled(false);
        binding.voiceButton.setVisibility(View.GONE);
        binding.voiceButtonExpanded.setEnabled(false);
        binding.voiceButtonExpanded.setVisibility(View.GONE);
    }

    private void enableLLMComponents() {
        binding.messageInput.setEnabled(true);
        binding.messageInput.setFocusable(true);
        binding.messageInput.setFocusableInTouchMode(true);
        binding.messageInput.setAlpha(ENABLED_ALPHA);
        
        binding.messageInputExpanded.setEnabled(true);
        binding.messageInputExpanded.setFocusable(true);
        binding.messageInputExpanded.setFocusableInTouchMode(true);
        binding.messageInputExpanded.setAlpha(ENABLED_ALPHA);
        
        binding.sendButton.setEnabled(true);
        binding.sendButton.setAlpha(ENABLED_ALPHA);
        binding.sendButtonExpanded.setEnabled(true);
        binding.sendButtonExpanded.setAlpha(ENABLED_ALPHA);
    }

    private void disableLLMComponents() {
        binding.messageInput.setEnabled(false);
        binding.messageInput.setFocusable(false);
        binding.messageInput.setAlpha(DISABLED_ALPHA);
        binding.messageInputExpanded.setEnabled(false);
        binding.messageInputExpanded.setFocusable(false);
        binding.messageInputExpanded.setAlpha(DISABLED_ALPHA);
        binding.sendButton.setEnabled(false);
        binding.sendButton.setAlpha(DISABLED_ALPHA);
        binding.sendButtonExpanded.setEnabled(false);
        binding.sendButtonExpanded.setAlpha(DISABLED_ALPHA);
    }

    private void enableVLMComponents() {
        binding.attachButton.setEnabled(true);
        binding.attachButton.setVisibility(View.VISIBLE);
        binding.attachButton.setAlpha(ENABLED_ALPHA);
        binding.attachButtonExpanded.setEnabled(true);
        binding.attachButtonExpanded.setVisibility(View.VISIBLE);
        binding.attachButtonExpanded.setAlpha(ENABLED_ALPHA);
    }

    private void disableVLMComponents() {
        binding.attachButton.setEnabled(false);
        binding.attachButton.setVisibility(View.GONE);
        binding.attachButtonExpanded.setEnabled(false);
        binding.attachButtonExpanded.setVisibility(View.GONE);
    }

    private void enableTTSComponents() {
        chatAdapter.setTTSEnabled(true);
    }

    private void disableTTSComponents() {
        chatAdapter.setTTSEnabled(false);
    }
}