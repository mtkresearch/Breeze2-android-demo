package com.mtkresearch.gai_android;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.widget.Toast;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.view.View;

import com.mtkresearch.gai_android.ChatActivity;
import com.mtkresearch.gai_android.R;
import com.mtkresearch.gai_android.databinding.ActivityMainBinding;
import com.mtkresearch.gai_android.service.ASREngineService;
import com.mtkresearch.gai_android.service.BaseEngineService;
import com.mtkresearch.gai_android.service.LLMEngineService;
import com.mtkresearch.gai_android.service.TTSEngineService;
import com.mtkresearch.gai_android.service.VLMEngineService;
import com.mtkresearch.gai_android.utils.NativeLibraryLoader;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 1;
    private ActivityMainBinding binding;
    
    // Services
    private LLMEngineService llmService;
    private VLMEngineService vlmService;
    private ASREngineService asrService;
    private TTSEngineService ttsService;
    
    // Service enable flags
    private boolean llmEnabled = false;
    private boolean vlmEnabled = false;
    private boolean asrEnabled = false;
    private boolean ttsEnabled = false;

    private static final String LLAMA_MODEL_PATH = "/data/local/tmp/llama/";
    
    // Model lists for each service
    private String[] llmModels;
    private String[] vlmModels;
    
    // Selected model for each service
    private String selectedLlmModel = null;
    private String selectedVlmModel = null;

    private final ServiceConnection llmConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            LLMEngineService.LocalBinder binder = (LLMEngineService.LocalBinder) service;
            llmService = binder.getService();
            initializeService(llmService, binding.llmStatusIndicator);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            llmService = null;
            updateEngineStatus(binding.llmStatusIndicator, EngineStatus.ERROR);
        }
    };

    private final ServiceConnection vlmConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            VLMEngineService.LocalBinder binder = (VLMEngineService.LocalBinder) service;
            vlmService = binder.getService();
            initializeService(vlmService, binding.vlmStatusIndicator);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            vlmService = null;
            updateEngineStatus(binding.vlmStatusIndicator, EngineStatus.ERROR);
        }
    };

    private final ServiceConnection asrConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ASREngineService.LocalBinder binder = (ASREngineService.LocalBinder) service;
            asrService = binder.getService();
            initializeService(asrService, binding.asrStatusIndicator);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            asrService = null;
            updateEngineStatus(binding.asrStatusIndicator, EngineStatus.ERROR);
        }
    };

    private final ServiceConnection ttsConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            TTSEngineService.LocalBinder binder = (TTSEngineService.LocalBinder) service;
            ttsService = binder.getService();
            initializeService(ttsService, binding.ttsStatusIndicator);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            ttsService = null;
            updateEngineStatus(binding.ttsStatusIndicator, EngineStatus.ERROR);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Load native libraries first
        try {
            NativeLibraryLoader.loadLibraries();
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
            Toast.makeText(this, "Failed to initialize: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Scan for available models
        scanForModels();

        // Setup UI before checking permissions
        setupUI();
        setupModelSpinners();
        setupServiceSwitches();
        displayDeviceInfo();
        
        // Initialize status indicators
        initializeStatusIndicators();
    }

    private void scanForModels() {
        File modelDir = new File(LLAMA_MODEL_PATH);
        if (!modelDir.exists() || !modelDir.isDirectory()) {
            Log.e(TAG, "Model directory not found: " + LLAMA_MODEL_PATH);
            Toast.makeText(this, "Model directory not found", Toast.LENGTH_LONG).show();
            return;
        }

        // Get all .pte files
        File[] modelFiles = modelDir.listFiles((dir, name) -> name.endsWith(".pte"));
        if (modelFiles == null) {
            Log.e(TAG, "Unable to list files in model directory");
            return;
        }

        // Create lists to store models by type
        List<String> llmList = new ArrayList<>();
        List<String> vlmList = new ArrayList<>();

        // Categorize models based on filename patterns
        for (File file : modelFiles) {
            String name = file.getName();
            if (name.contains("llava")) {
                vlmList.add(name);
            } else if (name.contains("llama") || name.contains("breeze")) {
                llmList.add(name);
            }
        }

        // Convert lists to arrays
        llmModels = llmList.toArray(new String[0]);
        vlmModels = vlmList.toArray(new String[0]);

        // Log found models
        Log.d(TAG, "Found LLM models: " + Arrays.toString(llmModels));
        Log.d(TAG, "Found VLM models: " + Arrays.toString(vlmModels));
    }

    private void setupUI() {
        setupChatButton();
        initializeStatusIndicators();
    }

    private void setupChatButton() {
        binding.startChatButton.setEnabled(false);
        binding.startChatButton.setBackgroundTintList(ColorStateList.valueOf(
            getResources().getColor(R.color.surface, getTheme())));
        binding.startChatButton.setTextColor(
            getResources().getColor(R.color.text_secondary, getTheme()));
            
        binding.startChatButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, ChatActivity.class);
            startActivity(intent);
        });
    }

    private void setupModelSpinners() {
        // Setup LLM model spinner if models are available
        if (llmModels != null && llmModels.length > 0) {
            ArrayAdapter<String> llmAdapter = new ArrayAdapter<>(this, 
                android.R.layout.simple_spinner_dropdown_item, llmModels);
            binding.llmModelSpinner.setAdapter(llmAdapter);
            binding.llmModelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    selectedLlmModel = llmModels[position];
                    // If service is already running, restart it with new model
                    if (llmEnabled && llmService != null) {
                        restartService(LLMEngineService.class, llmConnection, binding.llmStatusIndicator);
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                    selectedLlmModel = null;
                }
            });
        } else {
            binding.llmModelSpinner.setEnabled(false);
            binding.llmSwitch.setEnabled(false);
        }

        // Setup VLM model spinner if models are available
        if (vlmModels != null && vlmModels.length > 0) {
            ArrayAdapter<String> vlmAdapter = new ArrayAdapter<>(this, 
                android.R.layout.simple_spinner_dropdown_item, vlmModels);
            binding.vlmModelSpinner.setAdapter(vlmAdapter);
            binding.vlmModelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    selectedVlmModel = vlmModels[position];
                    if (vlmEnabled && vlmService != null) {
                        restartService(VLMEngineService.class, vlmConnection, binding.vlmStatusIndicator);
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                    selectedVlmModel = null;
                }
            });
        } else {
            binding.vlmModelSpinner.setEnabled(false);
            binding.vlmSwitch.setEnabled(false);
        }

        // Disable model spinners for ASR and TTS as they don't use model selection
        binding.asrModelSpinner.setEnabled(false);
        binding.ttsModelSpinner.setEnabled(false);
    }

    private void setupServiceSwitches() {
        binding.llmSwitch.setChecked(llmEnabled);
        binding.vlmSwitch.setChecked(vlmEnabled);
        binding.asrSwitch.setChecked(asrEnabled);
        binding.ttsSwitch.setChecked(ttsEnabled);

        binding.llmSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && selectedLlmModel == null) {
                Toast.makeText(this, "Please select a model first", Toast.LENGTH_SHORT).show();
                buttonView.setChecked(false);
                return;
            }
            
            llmEnabled = isChecked;
            if (isChecked) {
                startAndBindService(LLMEngineService.class, llmConnection, null);
                updateEngineStatus(binding.llmStatusIndicator, EngineStatus.INITIALIZING);
            } else if (llmService != null) {
                safeUnbindService(llmConnection);
                stopService(new Intent(this, LLMEngineService.class));
                llmService = null;
                updateEngineStatus(binding.llmStatusIndicator, EngineStatus.ERROR);
            }
            checkAllServicesReady();
        });

        binding.vlmSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && selectedVlmModel == null) {
                Toast.makeText(this, "Please select a model first", Toast.LENGTH_SHORT).show();
                buttonView.setChecked(false);
                return;
            }
            
            vlmEnabled = isChecked;
            if (isChecked) {
                startAndBindService(VLMEngineService.class, vlmConnection, null);
                updateEngineStatus(binding.vlmStatusIndicator, EngineStatus.INITIALIZING);
            } else if (vlmService != null) {
                safeUnbindService(vlmConnection);
                stopService(new Intent(this, VLMEngineService.class));
                vlmService = null;
                updateEngineStatus(binding.vlmStatusIndicator, EngineStatus.ERROR);
            }
            checkAllServicesReady();
        });

        binding.asrSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            asrEnabled = isChecked;
            if (isChecked) {
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.RECORD_AUDIO},
                        PERMISSION_REQUEST_CODE);
                    binding.asrSwitch.setChecked(false);
                    return;
                }
                startAndBindService(ASREngineService.class, asrConnection, null);
                updateEngineStatus(binding.asrStatusIndicator, EngineStatus.INITIALIZING);
            } else if (asrService != null) {
                safeUnbindService(asrConnection);
                stopService(new Intent(this, ASREngineService.class));
                asrService = null;
                updateEngineStatus(binding.asrStatusIndicator, EngineStatus.ERROR);
            }
            checkAllServicesReady();
        });

        binding.ttsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ttsEnabled = isChecked;
            if (isChecked) {
                startAndBindService(TTSEngineService.class, ttsConnection, null);
                updateEngineStatus(binding.ttsStatusIndicator, EngineStatus.INITIALIZING);
            } else if (ttsService != null) {
                safeUnbindService(ttsConnection);
                stopService(new Intent(this, TTSEngineService.class));
                ttsService = null;
                updateEngineStatus(binding.ttsStatusIndicator, EngineStatus.ERROR);
            }
            checkAllServicesReady();
        });
    }

    private void initializeStatusIndicators() {
        updateEngineStatus(binding.llmStatusIndicator, EngineStatus.ERROR);
        updateEngineStatus(binding.vlmStatusIndicator, EngineStatus.ERROR);
        updateEngineStatus(binding.asrStatusIndicator, EngineStatus.ERROR);
        updateEngineStatus(binding.ttsStatusIndicator, EngineStatus.ERROR);
    }

    private void displayDeviceInfo() {
        binding.deviceBrand.setText("Brand: " + Build.MANUFACTURER + " " + Build.MODEL);
        binding.deviceFirmware.setText("Firmware: " + Build.VERSION.RELEASE);
        binding.deviceChip.setText("Chipset: " + Build.HARDWARE);
        binding.deviceRam.setText(getMemoryInfo());
    }

    private String getMemoryInfo() {
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        activityManager.getMemoryInfo(memoryInfo);
        
        long totalMemory = memoryInfo.totalMem / (1024 * 1024); // Convert to MB
        long availableMemory = memoryInfo.availMem / (1024 * 1024);
        
        return String.format(Locale.getDefault(), "%d MB / %d MB", availableMemory, totalMemory);
    }

    private void startAndBindService(
            Class<? extends BaseEngineService> serviceClass, 
            ServiceConnection connection,
            Runnable onComplete) {
        Intent intent = new Intent(this, serviceClass);
        
        // Add model path to intent only for LLM and VLM services
        if (serviceClass == LLMEngineService.class && selectedLlmModel != null) {
            intent.putExtra("model_path", LLAMA_MODEL_PATH + selectedLlmModel);
        } else if (serviceClass == VLMEngineService.class && selectedVlmModel != null) {
            intent.putExtra("model_path", LLAMA_MODEL_PATH + selectedVlmModel);
        }
        
        startService(intent);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
        
        if (onComplete != null) {
            new Handler().postDelayed(onComplete, 500);
        }
    }

    private void initializeService(BaseEngineService service, ImageView statusIndicator) {
        if (service == null) {
            updateEngineStatus(statusIndicator, EngineStatus.ERROR);
            return;
        }

        updateEngineStatus(statusIndicator, EngineStatus.INITIALIZING);
        service.initialize()
            .thenAccept(success -> {
                runOnUiThread(() -> {
                    EngineStatus status = success ? EngineStatus.READY : EngineStatus.ERROR;
                    updateEngineStatus(statusIndicator, status);
                    checkAllServicesReady();
                });
            })
            .exceptionally(throwable -> {
                Log.e(TAG, "Service initialization failed", throwable);
                runOnUiThread(() -> {
                    updateEngineStatus(statusIndicator, EngineStatus.ERROR);
                    checkAllServicesReady();
                });
                return null;
            });
    }
    
    private void updateEngineStatus(ImageView indicator, EngineStatus status) {
        runOnUiThread(() -> {
            int colorRes;
            switch (status) {
                case INITIALIZING:
                    colorRes = R.drawable.status_indicator_yellow;
                    break;
                case READY:
                    colorRes = R.drawable.status_indicator_green;
                    break;
                case ERROR:
                    colorRes = R.drawable.status_indicator_red;
                    break;
                default:
                    colorRes = R.drawable.status_indicator_red;
                    break;
            }
            indicator.setImageResource(colorRes);
        });
    }

    private void checkAllServicesReady() {
        // Check if at least one service is enabled and ready
        boolean atLeastOneServiceReady = 
            (llmEnabled && llmService != null && llmService.isReady()) ||
            (vlmEnabled && vlmService != null && vlmService.isReady()) ||
            (asrEnabled && asrService != null && asrService.isReady()) ||
            (ttsEnabled && ttsService != null && ttsService.isReady());

        // Check if any service is enabled but not ready
        boolean anyServiceInitializing = 
            (llmEnabled && (llmService == null || !llmService.isReady())) ||
            (vlmEnabled && (vlmService == null || !vlmService.isReady())) ||
            (asrEnabled && (asrService == null || !asrService.isReady())) ||
            (ttsEnabled && (ttsService == null || !ttsService.isReady()));

        runOnUiThread(() -> {
            binding.startChatButton.setEnabled(atLeastOneServiceReady);

            if (atLeastOneServiceReady) {
                binding.startChatButton.setBackgroundTintList(ColorStateList.valueOf(
                    getResources().getColor(R.color.primary, getTheme())));
                binding.startChatButton.setTextColor(
                    getResources().getColor(R.color.text_primary, getTheme()));
            } else {
                binding.startChatButton.setBackgroundTintList(ColorStateList.valueOf(
                    getResources().getColor(R.color.surface, getTheme())));
                binding.startChatButton.setTextColor(
                    getResources().getColor(R.color.text_secondary, getTheme()));
                
                // Show initialization status only if any service is enabled and initializing
                if (anyServiceInitializing) {
                    Toast.makeText(this, "Waiting for services to initialize", 
                        Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private enum EngineStatus {
        INITIALIZING,
        READY,
        ERROR
    }

    private void restartService(Class<? extends BaseEngineService> serviceClass, 
                              ServiceConnection connection,
                              ImageView statusIndicator) {
        // Unbind and stop current service
        safeUnbindService(connection);
        stopService(new Intent(this, serviceClass));
        
        // Update status to initializing
        updateEngineStatus(statusIndicator, EngineStatus.INITIALIZING);
        
        // Start new service with delay
        new Handler().postDelayed(() -> {
            startAndBindService(serviceClass, connection, null);
        }, 500);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) {
            // Only stop services if activity is actually finishing
            stopService(new Intent(this, LLMEngineService.class));
            stopService(new Intent(this, VLMEngineService.class));
            stopService(new Intent(this, ASREngineService.class));
            stopService(new Intent(this, TTSEngineService.class));
        }
        // Always unbind
        safeUnbindService(llmConnection);
        safeUnbindService(vlmConnection);
        safeUnbindService(asrConnection);
        safeUnbindService(ttsConnection);
    }

    private void safeUnbindService(ServiceConnection connection) {
        try {
            unbindService(connection);
        } catch (IllegalArgumentException e) {
            // Service might not be bound
            Log.w(TAG, "Service was not bound", e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "RECORD_AUDIO permission granted");
                // Re-enable ASR switch if permission was granted
                binding.asrSwitch.setChecked(true);
            } else {
                Log.e(TAG, "RECORD_AUDIO permission denied");
                Toast.makeText(this, "Speech recognition requires audio permission", Toast.LENGTH_LONG).show();
                binding.asrSwitch.setChecked(false);
            }
        }
    }
} 