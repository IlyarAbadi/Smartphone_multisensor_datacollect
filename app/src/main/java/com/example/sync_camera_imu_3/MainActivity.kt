package com.example.sync_camera_imu_3

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.YuvImage
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.*
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null

    private lateinit var sensorFile: FileWriter
    private lateinit var sensorCsvFile: File
    private val imuBuffer = StringBuilder()

    private var isRecording = false
    private lateinit var analysisUseCase: ImageAnalysis
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val desiredFocusDistance = 1.0f

    companion object {
        private const val REQUEST_CODE_CAMERA = 100
        private const val FRAME_SAVE_INTERVAL = 1  // Save every 10th frame
    }

    private var frameCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        sensorCsvFile = File(getExternalFilesDir(null), "sensor_data.csv")

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CODE_CAMERA
            )
        }

        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener {
            if (!isRecording) startRecording()
        }

        findViewById<FloatingActionButton>(R.id.fab_stop).setOnClickListener {
            if (isRecording) stopRecording()
        }
    }

    private fun startRecording() {
        isRecording = true
        frameCount = 0

        sensorFile = FileWriter(sensorCsvFile, true)
        sensorFile.append("Timestamp,Sensor,X,Y,Z\n")

        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        magnetometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }

        Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording() {
        isRecording = false
        sensorManager.unregisterListener(this)

        if (::sensorFile.isInitialized) {
            if (imuBuffer.isNotEmpty()) {
                sensorFile.append(imuBuffer.toString())
                imuBuffer.clear()
            }
            sensorFile.flush()
            sensorFile.close()
            saveSensorCsvToDocuments("sensor_data.csv", sensorCsvFile.readText())
        }

        Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()
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

            // Low resolution for faster frame rate
            val resolution = Size(640, 480)

            val builder = ImageAnalysis.Builder()
                .setTargetResolution(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

            val camera2Extender = Camera2Interop.Extender(builder)
            camera2Extender.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_OFF
            )
            camera2Extender.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.LENS_FOCUS_DISTANCE,
                desiredFocusDistance
            )

            analysisUseCase = builder.build()

            analysisUseCase.setAnalyzer(analysisExecutor) { imageProxy ->
                if (isRecording) {
                    processFrame(imageProxy)
                }
                imageProxy.close()
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this as LifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysisUseCase
                )
            } catch (exc: Exception) {
                exc.printStackTrace()
                Toast.makeText(this, "Camera start failed", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(image: ImageProxy) {
        val timestamp = System.currentTimeMillis()
        frameCount++

        // Optionally save every Nth frame as JPEG
        if (frameCount % FRAME_SAVE_INTERVAL == 0) {
            val yuvBytes = yuvToJpegBytes(image)
            val file = File(getExternalFilesDir("Frames"), "frame_${timestamp}.jpg")
            FileOutputStream(file).use { it.write(yuvBytes) }
        }
    }

    private fun yuvToJpegBytes(image: ImageProxy): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, image.width, image.height), 80, out)
        return out.toByteArray()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (!isRecording) return
            val timestamp = System.currentTimeMillis()
            val type = when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> "Accelerometer"
                Sensor.TYPE_GYROSCOPE -> "Gyroscope"
                Sensor.TYPE_MAGNETIC_FIELD -> "Magnetometer"
                else -> "Unknown"
            }
            imuBuffer.append("$timestamp,$type,${it.values[0]},${it.values[1]},${it.values[2]}\n")

            if (imuBuffer.length > 5000) {
                sensorFile.append(imuBuffer.toString())
                imuBuffer.clear()
            }
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
                contentResolver.openOutputStream(outputUri).use { out ->
                    out?.write(content.toByteArray())
                }
            }
            Toast.makeText(this, "Sensor CSV saved to Documents", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_CAMERA &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission denied", Toast.LENGTH_LONG).show()
        }
    }
}
