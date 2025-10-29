package com.example.sync_camera_imu_3

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File
import java.io.FileWriter
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import android.util.Size
import android.util.Range


class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var imageCapture: ImageCapture
    private var captureCount = 0
    private val maxCaptures = 50000
    private val captureIntervalMs = 2L
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var autoCaptureRunnable: Runnable

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null
    private lateinit var sensorFile: FileWriter
    private lateinit var sensorCsvFile: File

    private var isRecording = false

    // Custom fixed focus in diopters (0=infinity, 0.5≈2m)
    private val desiredFocusDistance = 1.0f //0.5f

    companion object {
        private const val REQUEST_CODE_CAMERA = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        sensorCsvFile = File(getExternalFilesDir(null), "sensor_data.csv")

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CODE_CAMERA)
        }

        autoCaptureRunnable = object : Runnable {
            override fun run() {
                if (captureCount < maxCaptures && isRecording) {
                    takePhoto()
                    captureCount++
                    handler.postDelayed(this, captureIntervalMs)
                }
            }
        }

        val fabStart = findViewById<FloatingActionButton>(R.id.fab)
        fabStart.setOnClickListener {
            if (!isRecording) {
                startRecording()
                Toast.makeText(this, "Capture started", Toast.LENGTH_SHORT).show()
            }
        }

        val fabStop = findViewById<FloatingActionButton>(R.id.fab_stop)
        fabStop.setOnClickListener {
            if (isRecording) {
                stopRecording()
                Toast.makeText(this, "Capture stopped", Toast.LENGTH_SHORT).show()
            }
        }

        val fabCaptureOnly = findViewById<FloatingActionButton>(R.id.fab_capture_only)
        fabCaptureOnly.setOnClickListener {
            takePhotoOnly()
        }

    }

    private fun startRecording() {
        isRecording = true
        captureCount = 0

        sensorFile = FileWriter(sensorCsvFile, true)
        sensorFile.append("Timestamp,Sensor,X,Y,Z\n")
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        magnetometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }

        handler.postDelayed(autoCaptureRunnable, captureIntervalMs)
    }

    private fun stopRecording() {
        isRecording = false
        handler.removeCallbacks(autoCaptureRunnable)
        sensorManager.unregisterListener(this)
        if (::sensorFile.isInitialized) {
            sensorFile.flush()
            sensorFile.close()
            saveSensorCsvToDocuments("sensor_data.csv", sensorCsvFile.readText())
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val previewView = findViewById<PreviewView>(R.id.previewView)
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

//            val resolutionSelector = ResolutionSelector.Builder()
//                .setResolutionStrategy(
//                    ResolutionStrategy(
//                        Size(1280, 720), // desired resolution
//                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER
//                    )
//                )
//                .build()

//            val builder = ImageCapture.Builder()
//                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setResolutionSelector(resolutionSelector)

            val builder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)

            // Camera2Interop: fixed custom focus
            val camera2Extender = Camera2Interop.Extender(builder)
            camera2Extender.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_OFF
            )
            camera2Extender.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(20, 20)
            )
            camera2Extender.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.LENS_FOCUS_DISTANCE,
                desiredFocusDistance
            )

            imageCapture = builder.build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.bindToLifecycle(
                    this as LifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } catch (exc: Exception) {
                exc.printStackTrace()
                Toast.makeText(this, "Failed to start camera", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val timestamp = System.currentTimeMillis()
        val filename = "IMG_$timestamp.jpg"
        val storageDir = getExternalFilesDir("Pictures")
        val photoFile = File(storageDir, filename)

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    exc.printStackTrace()
                    Toast.makeText(applicationContext, "Photo capture failed", Toast.LENGTH_LONG).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    try {
                        val contentValues = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SyncCameraApp")
                            }
                        }
                        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                        uri?.let {
                            contentResolver.openOutputStream(uri).use { out: OutputStream? ->
                                photoFile.inputStream().copyTo(out!!)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(applicationContext, "Failed to save to gallery", Toast.LENGTH_LONG).show()
                    }
                }
            })
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            val timestamp = System.currentTimeMillis()
            val type = when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> "Accelerometer"
                Sensor.TYPE_GYROSCOPE -> "Gyroscope"
                Sensor.TYPE_MAGNETIC_FIELD -> "Magnetometer"
                else -> "Unknown"
            }
            val line = "$timestamp,$type,${it.values[0]},${it.values[1]},${it.values[2]}\n"
            if (::sensorFile.isInitialized && isRecording) sensorFile.append(line)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) stopRecording()
    }

    private fun saveSensorCsvToDocuments(fileName: String, content: String) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/SyncCameraApp")
                }
            }
            val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
            uri?.let { outputUri ->
                contentResolver.openOutputStream(outputUri).use { out: OutputStream? ->
                    out?.write(content.toByteArray())
                }
            }
            Toast.makeText(this, "Sensor CSV saved to Documents", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to save sensor CSV", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_CAMERA && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission denied", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------- IMAGE-ONLY (NO IMU) -------------------
    private fun takePhotoOnly() {
        val timestamp = System.currentTimeMillis()
        val filename = "$timestamp.jpg"
        val storageDir = getExternalFilesDir("Pictures")
        val photoFile = File(storageDir, filename)

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    exc.printStackTrace()
                    Toast.makeText(applicationContext, "Photo capture failed", Toast.LENGTH_LONG).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Toast.makeText(applicationContext, "Photo saved (no IMU): $filename", Toast.LENGTH_SHORT).show()
                }
            })
    }
}
