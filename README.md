[! Coming up soon.]
# Repository to collect data on smartphones using a fixed focal length (camera), IMU, and magnetometer.

This repository offers an ideal platform to collect data in the indoor environment with a smartphone. Fixed focal length is important in preserving the intrinsic calibration parameters of a camera.
The use cases of this app are:
-Indoor 3D reconstruction
-Simultaneous localization and mapping

The output data images are saved on the phone with an image name and tagged with a timestamp. Further, a CSV file is saved with the outputs from the triaxial gyroscope, accelerometer, and magnetometer.


The repository offers the following features:
- Image only capture.
 - Two buttons to capture and stop IMU only. Ideal for IMU intrinsic calibration
- Image-IMU capture.
 - Two buttons to capture and stop Image-IMU data. Ideal for SLAM and indoor reconstruction data collection
- Image only capture
 - One button to capture images. Ideal for camera calibration.   

A screenshot of the developed App is shown below:

<img src="/assets/Phone_screenshot.png" alt="Alt text" width="200"/>


# How to change FF, Exposure time, and Sensor Sensitivity.

The images are set to be captured with a fixed focal length, ideal for applications such as SLAM and indoor 3D reconstruction. Currently, the focal length can only be changed programmatically. The Private Val can be found in MainActivity.kt

`private val desiredFocusDistance = 1.0f`

In order to avoid motion blur, the exposure time should be lowered. Exposure time can be set in MainActivity.kt. In the example below, the exposure time is set to 1,700,000 microseconds, which translates to 1.7 seconds.

`camera2Extender.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME,1_700_000L//)`

Changing the exposure time should be cautiously achieved. Lower exposure time will result in less light accumulation and thus dimmer images. In order to counteract this, Sensor Sensitivity can be modified in MainActivity.kt. In the example below, sensor sensitivity is set to ISO 2000. Larger numbers will result in brighter images, but with higher noise. The user should adjust this value according to their needs. 

`camera2Extender.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY,2000  // ISO 200)`





