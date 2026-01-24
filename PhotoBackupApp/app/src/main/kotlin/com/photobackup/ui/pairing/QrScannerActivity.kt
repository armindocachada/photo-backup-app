package com.photobackup.ui.pairing

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.photobackup.databinding.ActivityQrScannerBinding
import com.photobackup.util.PreferencesManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

@AndroidEntryPoint
class QrScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrScannerBinding
    private lateinit var cameraExecutor: ExecutorService
    private var isProcessing = false

    @Inject
    lateinit var preferencesManager: PreferencesManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required to scan QR code", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.cancelButton.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        checkCameraPermission()
    }

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                startCamera()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, QrCodeAnalyzer { barcode ->
                        runOnUiThread {
                            handleBarcode(barcode)
                        }
                    })
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleBarcode(barcode: Barcode) {
        if (isProcessing) return
        isProcessing = true

        val rawValue = barcode.rawValue ?: return

        try {
            val json = JSONObject(rawValue)

            val serverId = json.optString("id", "")
            val apiKey = json.optString("key", "")

            if (serverId.isEmpty() || apiKey.isEmpty()) {
                Toast.makeText(this, "Invalid QR code format", Toast.LENGTH_SHORT).show()
                isProcessing = false
                return
            }

            // Save pairing data (server IP is discovered via mDNS, not stored)
            lifecycleScope.launch {
                preferencesManager.savePairingData(serverId, apiKey)
                preferencesManager.setSetupComplete(true)

                Toast.makeText(
                    this@QrScannerActivity,
                    "Paired successfully with server",
                    Toast.LENGTH_SHORT
                ).show()

                setResult(RESULT_OK, Intent().apply {
                    putExtra(EXTRA_SERVER_ID, serverId)
                })
                finish()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse QR code", e)
            Toast.makeText(this, "Invalid QR code", Toast.LENGTH_SHORT).show()
            isProcessing = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "QrScannerActivity"
        const val EXTRA_SERVER_ID = "server_id"
    }
}

private class QrCodeAnalyzer(
    private val onBarcodeDetected: (Barcode) -> Unit
) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient()

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: androidx.camera.core.ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    barcodes.firstOrNull { it.valueType == Barcode.TYPE_TEXT }?.let { barcode ->
                        onBarcodeDetected(barcode)
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }
}
