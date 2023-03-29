package com.example.cameraex

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import kotlinx.android.synthetic.main.activity_main.*
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.lang.Math.*
import java.nio.ByteBuffer
import java.text.SimpleDateFormat


class MainActivity : AppCompatActivity(), SensorEventListener {

    private val tagName = MainActivity::class.java.simpleName

    private var cameraDevice: CameraDevice? = null
    private var mPreviewBuilder: CaptureRequest.Builder? = null
    private var mPreviewSession: CameraCaptureSession? = null
    private var manager: CameraManager? = null

    //카메라 설정에 관한 멤버 변수
    private var mPreviewSize: Size? = null
    private var map: StreamConfigurationMap? = null

    //권한 멤버 변수
    private val requestCode: Int = 200
    private val permissionArray: Array<String> =
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    // 두 번 터치 변수
    private var touchCount:Int = 0 // 터치 누적 횟수
    private var DELAY:Long = 230 // handler delay, 230 -> 0.23

    // 물체 Box onoff 변수
    private var boxOnOff:Int = 0

    // 센서 lateinit -> 나중에 초기화
    // 아무것도 대입안하고 사용하려고 하면 강제종료.
    lateinit var sensorManager: SensorManager


    // 카메라 surfaceView 설정 시작
    private val mSurfaceTextureListener = object : TextureView.SurfaceTextureListener {

        //TextureView 생성될시 Available 메소드가 호출된다.
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
            // cameraManager 생성
            openCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(
            surface: SurfaceTexture?,
            width: Int,
            height: Int
        ) {
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?) = false
    }

    //카메라 연결 상태 콜백
    private val mStateCallBack = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            //CameraDevice 객체 생성
            cameraDevice = camera

            //CaptureRequest.Builder 객체와 CaptureSession 객체 생성하여 미래보기 화면을 실행
            startPreview()
        }

        override fun onDisconnected(camera: CameraDevice) {}
        override fun onError(camera: CameraDevice, error: Int) {}
    }

    // onCreate 시작
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //권한 체크하기
        if (checkPermission()) {
            initLayout()
        } else {
            ActivityCompat.requestPermissions(this, permissionArray, requestCode)
        }
//        ib_camera.setOnClickListener {
//            takePicture()
//        }
        if (OpenCVLoader.initDebug()) {
            println("MainActivity: Opencv is loaded")
        }
        else {
            println("MainActivity: Opencv falide to load")
        }
    }

    override fun onResume() {
        // onResume 위에 센서 설정을 해야 한다.
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE), SensorManager.SENSOR_DELAY_NORMAL)
        super.onResume()
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    // <------------------------------------------------------------------------------------------------------------------------>
    // 기타 함수들 시작

    // 카메라 함수 시작
    /**
     * 권한 체크하기
     */
    private fun checkPermission(): Boolean {
        //권한 요청
        return !(ContextCompat.checkSelfPermission(
            this,
            permissionArray[0]
        ) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this,
                    permissionArray[1]
                ) != PackageManager.PERMISSION_GRANTED)
    }

    /**
     * 권한 요청에 관한 callback 메소드 구현
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == this.requestCode && grantResults.isNotEmpty()) {
            var permissionGranted = true
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    //사용자가 권한을 거절했을 시
                    permissionGranted = false
                    break
                }
            }

            //권한을 모두 수락했을 경우
            if (permissionGranted) {
                initLayout()
            } else {
                //권한을 수락하지 않았을 경우
                ActivityCompat.requestPermissions(this, permissionArray, requestCode)
            }
        }
    }

    /**
     * 레이아웃 전개하기
     */
    private fun initLayout() {
        setContentView(R.layout.activity_main)
        preview.surfaceTextureListener = mSurfaceTextureListener
    }

    /**
     * CameraManager 생성
     * 카메라에 관한 정보 얻기
     * openCamera() 메소드 호출 -> CameraDevice 객체 생성
     */
    private fun openCamera(width: Int, height: Int) {
        //카메라 매니저를 생성한다.
        manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager?
        //기본 카메라를 선택한다.
        val cameraId = manager!!.cameraIdList[0]

        //카메라 특성을 가져오기
        val characteristics: CameraCharacteristics =
            manager!!.getCameraCharacteristics(cameraId)
        val level = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        val fps =
            characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        // Log.d(tagName, "최대 프레임 비율 : ${fps[fps.size - 1]} hardware level : $level")

        //StreamConfigurationMap 객체에는 카메라의 각종 지원 정보가 담겨져있다.
        map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        //미리보기용 textureView 화면 크기를 설정한다. (제공할 수 있는 최대 크기)
        mPreviewSize = map!!.getOutputSizes(SurfaceTexture::class.java)[0]
        val fpsForVideo = map!!.highSpeedVideoFpsRanges

//        Log.e(
//            tagName,
//            "for video ${fpsForVideo[fpsForVideo.size - 1]} preview Size width: ${mPreviewSize!!.width} height : $height"
//        )

        //권한 체크
        if (checkPermission()) {
            //CameraDevice 생
            manager!!.openCamera(cameraId, mStateCallBack, null)
        } else {
            ActivityCompat.requestPermissions(this, permissionArray, requestCode)
        }
    }

    /**
     * Preview 시작
     */
    private fun startPreview() {
        if (cameraDevice == null || !preview.isAvailable || mPreviewSize == null) {
            Log.e(tagName, "startPreview() fail, return")
            return
        }

        val texture = preview.surfaceTexture
        val surface = Surface(texture)

        mPreviewBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        mPreviewBuilder!!.addTarget(surface)

        cameraDevice!!.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    mPreviewSession = session
                    updatePreview()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {}
            },
            null
        )
    }

    /**
     * 업데이트 Preview
     */
    private fun updatePreview() {
        cameraDevice?.let {
            val fps = android.util.Range.create(20, 20)
            mPreviewBuilder!!.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
//            mPreviewBuilder!!.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            val thread = HandlerThread("CameraPreview")
            thread.start()

            val backgroundHandler = Handler(thread.looper)
            mPreviewSession!!.setRepeatingRequest(
                mPreviewBuilder!!.build(),
                null,
                backgroundHandler
            )
        }
    }

    // 파일 이름 중복 제거
    private fun newJpgFileName() : String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss")
        val filename = sdf.format(System.currentTimeMillis())
        return "${filename}.jpg"
    }

    /**
     * 사진 캡처
     */
    private fun takePicture() {
        var jpegSizes: Array<Size>? = map?.getOutputSizes(ImageFormat.JPEG)

        var width = 640
        var height = 480

        if (jpegSizes != null && jpegSizes.isNotEmpty()) {
            width = jpegSizes[0].width
            height = jpegSizes[1].height
        }

        val imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1)
        val outputSurfaces = mutableListOf<Surface>()
        outputSurfaces.add(imageReader.surface)
        outputSurfaces.add(Surface(preview.surfaceTexture))

        val captureBuilder =
            cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        captureBuilder.addTarget(imageReader.surface)

        //이미지가 캡처되는 순간에 제대로 사진 이미지가 나타나도록 3A를 자동으로 설정한다.
        captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        val rotation = windowManager.defaultDisplay.rotation
        captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, 90)

        val file = File(Environment.getExternalStorageDirectory(),  newJpgFileName())

        // 이미지를 캡처할 때 자동으로 호출된다.
        val readerListener = object : ImageReader.OnImageAvailableListener {
            override fun onImageAvailable(reader: ImageReader?) {
                imageReader?.let {
                    var image: Image? = null
                    image = imageReader.acquireLatestImage()
                    val buffer: ByteBuffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.capacity())
                    buffer.get(bytes)
                    save(bytes)
                }
            }

            private fun save(bytes: ByteArray) {
                val output: OutputStream? = FileOutputStream(file)
                output?.let {
                    it.write(bytes)
                    output.close()
                }
            }
        }

        //이미지를 캡처하는 작업은 메인 스레드가 아닌 스레드 핸들러로 수행한다.
        val thread = HandlerThread("CameraPicture")
        thread.start()
        val backgroundHandler = Handler(thread.looper)

        // imageReader 와 ImageReader.OnImageAvailableListener 객체를 서로 연결시키기 위해 설정한다.
        imageReader.setOnImageAvailableListener(readerListener, backgroundHandler)

        val captureCallBack = object : CameraCaptureSession.CaptureCallback() {

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                super.onCaptureCompleted(session, request, result)
                Toast.makeText(this@MainActivity, "사진이 캡처되었습니다.", Toast.LENGTH_SHORT).show()
                startPreview()
            }
        }

        //사진 이미지를 캡처하는데 사용하는 CameraCaptureSession 생성한다.
        // 이미 존재하면 기존 세션은 자동으로 종료
        cameraDevice!!.createCaptureSession(
            outputSurfaces,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    session.capture(captureBuilder.build(), captureCallBack, backgroundHandler)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {

                }
            },
            backgroundHandler
        )
    }

    // 카메라 함수 종료 <------------------------------------------------------------------------------------------------------------------------>

    // 화면 한 번 터치시 view를 생성 한다.
    // 화면 두 번 터치시 사진 캡처를 한다.
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        when (event?.action) {
            MotionEvent.ACTION_DOWN -> {
                touchCount++
                edgeDetection(event)
                //moveView(circleView, event)
                if (boxOnOff == 0) {
//                    circleView.visibility = View.VISIBLE
                    edgeView.visibility = View.VISIBLE
                    boxOnOff = 1
                } else {
//                    circleView.visibility = View.INVISIBLE
                    edgeView.visibility = View.INVISIBLE
                    boxOnOff = 0
                }
            }
            MotionEvent.ACTION_UP -> {
                edgeDetection(event)
                //moveView(circleView, event)
                Handler().postDelayed({
                    if (touchCount > 0)
                        touchCount-- }, DELAY)
            }
            MotionEvent.ACTION_MOVE -> {
                edgeDetection(event)
                //moveView(circleView, event)
            }
        }

        if (touchCount == 2) {
            takePicture()
            if (touchCount > 0)
                touchCount = 0
        }
        return super.onTouchEvent(event)
    }

    // boxView를 터치한 곳으로 옮기는 함수
    fun moveView(v: View, event: MotionEvent): Boolean {
        val parentWidth = (v.parent as ViewGroup).width // 부모 View 의 Width
        val parentHeight = (v.parent as ViewGroup).height // 부모 View 의 Height
        // view 왼쪽 위를 v.x, v.y로 해야함
        v.x = event.x - v.width / 2
        v.y = event.y - v.height / 2

        // println("event.x : " + event.x + " event.y : " + event.y) // event.x : 1034.121 event.y : 2155.9832

        if (v.x < 0) {
            v.setX(0f)
        } else if (v.x + v.width > parentWidth) {
            v.x = (parentWidth - v.width).toFloat()
        }
        if (v.y < 0) {
            v.setY(0f)
        } else if (v.y + v.height > parentHeight) {
            v.y = (parentHeight - v.height).toFloat()
        }
        return true
    }

    // 자이로스코프를 통해 뷰를 이동한다.
    fun moveObjectToGyroscope(v: View, str : String, value: Float) {
        val density = resources.displayMetrics.density
        var constantNum_vct_x = 90f * density
        var constantNum_vct_y = 120f * density
        var constantNum_vct_z = 50f * density
        var dp_x = pxToDp(v.x)
        var dp_y = pxToDp(v.y)
        var dpMax_x = px2dp(getDevicePx("deviceWidth"), this)// 360
        var dpMax_y = px2dp(getDevicePx("deviceHeight"), this)// 701
        var aspect_ratio = 480/360

        when (str) {
            "x" -> {
                v.y = v.y + constantNum_vct_y*value
            }
            "y" -> {
                v.x = v.x + constantNum_vct_x*value
            }
            "z" -> {
                when {
                    dp_x < dpMax_x / 2 && dp_y < dpMax_y / 2-> {
                        v.x = v.x + constantNum_vct_z*value
                        v.y = v.y - constantNum_vct_z*value*aspect_ratio
                    }
                    dp_x >= dpMax_x / 2 && dp_y < dpMax_y / 2-> {
                        v.x = v.x + constantNum_vct_z*value
                        v.y = v.y + constantNum_vct_z*value*aspect_ratio
                    }
                    dp_x < dpMax_x / 2 && dp_y >= dpMax_y / 2-> {
                        v.x = v.x - constantNum_vct_z*value
                        v.y = v.y - constantNum_vct_z*value*aspect_ratio
                    }
                    dp_x >= dpMax_x / 2 && dp_y >= dpMax_y / 2-> {
                        v.x = v.x - constantNum_vct_z*value
                        v.y = v.y + constantNum_vct_z*value*aspect_ratio
                    }
                }
            }
        }
//      objectOutOfLimit(circleView, "x")
//      objectOutOfLimit(circleView, "y")
    }

    fun pxToDp(px : Float) : Float {
        val density = resources.displayMetrics.density
        val value = (px / density).toFloat()
        return value
    }

    // 센서를 다루는 함수
    // 자이로스코프 센서를 사용
    override fun onSensorChanged(event: SensorEvent?) {

        val x = event?.values?.get(0) as Float // y축 기준으로 핸드폰 앞쪽(시계) - 뒤로(반시계) +
        val y = event?.values?.get(1) as Float // z축 기준으로 핸드폰 시계 - 반시계 +
        val z = event?.values?.get(2) as Float // x축 기준으로 핸드폰 시계 - 반시계 +

        //println(x.toString() + ", " + y.toString() + ", " + z.toString())

        if (boxOnOff == 1) {
//            moveObjectToGyroscope(circleView, "x", x);
//            moveObjectToGyroscope(circleView, "y", y);
//            moveObjectToGyroscope(circleView, "z", z);
            moveObjectToGyroscope(edgeView, "x", x);
            moveObjectToGyroscope(edgeView, "y", y);
            moveObjectToGyroscope(edgeView, "z", z);
        }
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
    }

    // 볼륨 키를 누르면 실행 되는 함수.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                //Toast.makeText(this, "Volume Up Pressed", Toast.LENGTH_SHORT).show()
                exit()
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                //Toast.makeText(this, "Volume Down Pressed", Toast.LENGTH_SHORT).show()
                exit()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // 종료
    fun exit() {
        ActivityCompat.finishAffinity(this)
        System.exit(0)
    }

    // <------------------------------------------------------------------------------------------------------------------------>
    // 20일 이후 추가.

    // bitmap을 회색으로 변경하고 반환한다.
    fun makeGray(bitmap: Bitmap) : Bitmap {

        // Create OpenCV mat object and copy content from bitmap
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        // Convert to grayscale
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGB2GRAY)

        // Make a mutable bitmap to copy grayscale image
        val grayBitmap = bitmap.copy(bitmap.config, true)
        Utils.matToBitmap(mat, grayBitmap)

        return grayBitmap
    }

    fun ImagePixelLog() {
        val texture = preview.surfaceTexture
        val surface = Surface(texture)

        val bitmap = makeGray(preview.getBitmap())
        val canvas = Canvas(bitmap)
        preview.draw(canvas)
    }

    fun ImageToGrayedgeView() {
        val bitmap = makeGray(preview.getBitmap())
        edgeView.setImageBitmap(bitmap)
    }

    // preview의 edge를 detection하여 edgeView에 보여준다.
    fun edgeDetectionPreView() {
        val bitmap = makeGray(preview.getBitmap())

        val image = Mat()
        val edge = Mat()
        Utils.bitmapToMat(bitmap, image)

        Imgproc.Canny(image, edge, 0.0, 100.0)

        val edgeBitmap = bitmap.copy(bitmap.config, true)
        Utils.matToBitmap(edge, edgeBitmap)

        edgeView.setImageBitmap(edgeBitmap)
    }

    // Kotlin-side mask Bitmap to Mat converter
    fun Bitmap.maskToMat(view : View): Mat {
        val mat = Mat(view.width, view.height, CvType.CV_8UC1)
        val obj = copy(Bitmap.Config.ARGB_8888, true)
        Utils.bitmapToMat(obj, mat)
        Imgproc.cvtColor(mat, mat, CvType.CV_8UC1)
        Imgproc.cvtColor(mat, mat, Imgcodecs.IMREAD_GRAYSCALE)
        return mat
    }

    // Original image Bitmap to Mat converter
    fun Bitmap.objToMat(view : View): Mat {
        val mat = Mat(view.width, view.height, CvType.CV_8UC1)
        val obj = copy(Bitmap.Config.ARGB_8888, true)
        Utils.bitmapToMat(obj, mat)
        return mat
    }

    // preview의 edge를 detection하여 edgeView에 보여준다.
    fun edgeDetection(event: MotionEvent) {
//        var bitmap = makeGray(preview.bitmap)
        var bitmap = preview.bitmap

        if (cutBitmap(edgeView, event, bitmap) != null) bitmap = cutBitmap(edgeView, event, bitmap)!!
        val image = Mat()
        var edge = Mat()

        Utils.bitmapToMat(bitmap, image)
        // Canny Detection, HoughLine Detection
        //edge = LineAndEdgeDetection(image)
        edge = LineDetection(bitmap, image)

        var edgeBitmap = bitmap.copy(bitmap.config, true)
        Utils.matToBitmap(edge, edgeBitmap)

        // 검은 배경을 투명하게, 흰색 부분을 밝게
//        edgeBitmap = makeTransparent(edgeBitmap)

        edgeView.setImageBitmap(edgeBitmap)
        moveView(edgeView, event)
    }

    fun cutBitmap(edgeV : View, event: MotionEvent, original: Bitmap): Bitmap? {
        val v : View = edgeV

        val deviceWidth = getDevicePx("deviceWidth") // 1080
        val deviceHeight = getDevicePx("deviceHeight") // 2105
        val maxHeight = preview.height // 1440

        // 위아래 빈공간
        val emptySpace = (deviceHeight - maxHeight) / 2 // 332.5

        val parentWidth = (v.parent as ViewGroup).width // 1080
        val parentHeight = (v.parent as ViewGroup).height - emptySpace // 2172

        v.x = (event.x - v.width / 2)
        v.y = (event.y - v.height / 2)

        // 아래 부분 남는 곳 처리.
        // 1170~1180 넘으면 꺼짐 패딩이라도 있는듯. 1140 - 180 = 1260까지 되는게 아니다.
        val paddingTemp = 77
        if (v.y > deviceHeight - emptySpace - v.height + paddingTemp) v.y = (deviceHeight - emptySpace - v.height + paddingTemp).toFloat()

        // y 좌표 튜닝
        v.y += emptySpace / 30

        if (v.x < 0) {
            v.setX(0f)
        } else if (v.x + v.width > deviceWidth) {
            v.x = (deviceWidth - v.width).toFloat()
        }
        if (v.y < 0) {
            v.setY(0f)
        }
        else if (v.y + v.height > deviceHeight) {
            v.y = (deviceHeight - v.height).toFloat()
        }

        // 윗 부분 남는 곳 처리.
        if (v.y > emptySpace) v.y -= emptySpace
        else if (v.y < emptySpace) v.y = 0f

        var x1 : Int = v.x.toInt()
        var y1 : Int = v.y.toInt()

        println("x1 : $x1 y1 : $y1")
        println("event.x : " + event.x + " event.y : " + event.y) // event.x : 1034.121 event.y : 2155.9832

        var result = Bitmap.createBitmap( original // 0, 0이 왼쪽 위다.
            , x1//X 시작위치
            , y1//Y 시작위치
            , original.width / 4 // 넓이 (360/480 -> 90/120)
            , original.width / 4
        ) // 높이

//        if (result != original) {
//            original.recycle()
//        }
        return result
    }

    // Px과 Dp를 다루는 함수들
    fun getDevicePx(str: String): Int {
        val display = this.applicationContext?.resources?.displayMetrics
        var deviceWidth = display?.widthPixels
        var deviceHeight = display?.heightPixels
//        deviceWidth = px2dp(deviceWidth!!, this)
//        deviceHeight = px2dp(deviceHeight!!, this)
//        Log.d("deviceSize", "${deviceWidth}")
//        Log.d("deviceSize", "${deviceHeight}")

        when (str) {
            "deviceWidth" -> return deviceWidth!!
            "deviceHeight" -> return deviceHeight!!
            else -> return 0
        }
    }

    fun px2dp(px: Int, context: Context): Int {
        return px / ((context.resources.displayMetrics.densityDpi.toFloat()) / DisplayMetrics.DENSITY_DEFAULT).toInt()
    }

    fun LineAndEdgeDetection(image: Mat) : Mat {
        val edge = Mat()
        val lines = Mat()

        //Utils.bitmapToMat(bitmap, image)
        // Canny Detection
        Imgproc.Canny(image, edge, 150.0, 200.0)
        // HoughLine Detection
        Imgproc.HoughLines(edge, lines, 1.0, Math.PI/180.0, 100)
        // Edge to Color
        // Imgproc.cvtColor(edge, edge, Imgproc.COLOR_GRAY2BGR)

        // Detection Lines Draw
        for (x in 0 until lines.rows()) {
            val rho = lines.get(x, 0)[0]
            val theta = lines.get(x, 0)[1]
            val a = cos(theta)
            val b = sin(theta)
            val x0 = a * rho
            val y0 = b * rho
            val pt1 = org.opencv.core.Point(round(x0 + 5000*(-b)).toDouble(), round(y0 + 5000*(a)).toDouble())
            val pt2 = org.opencv.core.Point(round(x0 - 5000*(-b)).toDouble(), round(y0 - 5000*(a)).toDouble())
            Imgproc.line(edge, pt1, pt2, Scalar(255.0,255.0,255.0), 2)
        }
        return edge
    }

    fun LineDetection(bit : Bitmap, image: Mat) : Mat {
        val edge = Mat()
        val lines = Mat()

        // Canny Detection
        Imgproc.Canny(image, edge, 150.0, 200.0)
        // HoughLine Detection
        Imgproc.HoughLines(edge, lines, 1.0, Math.PI/180.0, 100)
        // Edge to Color
        // Imgproc.cvtColor(edge, edge, Imgproc.COLOR_GRAY2BGR)

        // 투명 bitmap 하나 만들기.
        val result = Mat()
        val allpixels = IntArray(bit.height * bit.width)
        for (i in 0 until bit.height * bit.width) {
            allpixels[i] = Color.alpha(Color.TRANSPARENT)
        }
        bit.setPixels(allpixels, 0, bit.width, 0, 0, bit.width, bit.height)
        Utils.bitmapToMat(bit, result)

        // Detection Lines Draw
        for (x in 0 until lines.rows()) {
            val rho = lines.get(x, 0)[0]
            val theta = lines.get(x, 0)[1]
            val a = cos(theta)
            val b = sin(theta)
            val x0 = a * rho
            val y0 = b * rho
            val pt1 = org.opencv.core.Point(round(x0 + 5000*(-b)).toDouble(), round(y0 + 5000*(a)).toDouble())
            val pt2 = org.opencv.core.Point(round(x0 - 5000*(-b)).toDouble(), round(y0 - 5000*(a)).toDouble())
            Imgproc.line(result, pt1, pt2, Scalar(255.0,255.0,255.0), 2)
        }
        return result
    }

    // bitmap 투명하게 변환
    private fun makeTransparent(bit: Bitmap): Bitmap? {
        val width = bit.width
        val height = bit.height
        val myBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val allpixels = IntArray(myBitmap.height * myBitmap.width)
        bit.getPixels(allpixels, 0, myBitmap.width, 0, 0, myBitmap.width, myBitmap.height)
        myBitmap.setPixels(allpixels, 0, width, 0, 0, width, height)
        for (i in 0 until myBitmap.height * myBitmap.width) {
            if (allpixels[i] == Color.WHITE) { // 하얀색을 밝은 색으로
                allpixels[i] = Color.MAGENTA
            } else if (allpixels[i] == Color.BLACK) { // 검은 색을 투명하게
                allpixels[i] = Color.alpha(Color.TRANSPARENT)
            }
        }
        myBitmap.setPixels(allpixels, 0, myBitmap.width, 0, 0, myBitmap.width, myBitmap.height)
        return myBitmap
    }

    //  bitmap 흑백으로 변환
    private fun grayScale(orgBitmap: Bitmap): Bitmap? {
        Log.i("gray", "in")
        val width: Int
        val height: Int
        width = orgBitmap.width
        height = orgBitmap.height
        val bmpGrayScale = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_4444)

        // color information
        var A: Int
        var R: Int
        var G: Int
        var B: Int
        var pixel: Int

        // scan through all pixels
        for (x in 0 until width) {
            for (y in 0 until height) {
                // get pixel color
                pixel = orgBitmap.getPixel(x, y)
                A = Color.alpha(pixel)
                R = Color.red(pixel)
                G = Color.green(pixel)
                B = Color.blue(pixel)
                var gray = (0.2989 * R + 0.5870 * G + 0.1140 * B).toInt()

                // use 128 as threshold, above -> white, below -> black
                gray = if (gray > 128) 255 else 0
                // set new pixel color to output bitmap
                bmpGrayScale.setPixel(x, y, Color.argb(A, gray, gray, gray))
            }
        }
        return bmpGrayScale
    }



    // <------------------------------------------------------------------------------------------------------------------------>
    // 20일 이전 추가
    fun objectOutOfLimit(v: View, str : String) {
        val parentWidth = (v.parent as ViewGroup).width // 부모 View 의 Width
        val parentHeight = (v.parent as ViewGroup).height // 부모 View 의 Height

        when (str) {
            "x" -> {
                if (v.x < 0) {
                    v.setX(0f)
                }
                else if (v.x + v.width > parentWidth) {
                    v.x = (parentWidth - v.width).toFloat()
                }
            }
            "y" -> {
                if (v.y < 0) {
                    v.setY(0f)
                }
                else if (v.y + v.height > parentHeight) {
                    v.y = (parentHeight - v.height).toFloat()
                }
            }
        }
    }

    // 터치시 생성 할 boxView
    fun createBox() : View{
        val boxView = TextView(this)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        boxView.layoutParams = lp
        boxView.background = ContextCompat.getDrawable(this, R.drawable.custom_square)
        boxView.id = ViewCompat.generateViewId()

        boxView.visibility = View.VISIBLE
        return boxView
    }

    fun getDeviceDpi(): String {
        val density = resources.displayMetrics.density
        val result = when {
            density >= 4.0 -> "xxxhdpi"
            density >= 3.0 -> "xxhdpi"
            density >= 2.0 -> "xhdpi"
            density >= 1.5 -> "hdpi"
            density >= 1.0 -> "mdpi"
            else -> "ldpi"
        }
        println("gimgongta log dpi : $result")
        return result
    }
}