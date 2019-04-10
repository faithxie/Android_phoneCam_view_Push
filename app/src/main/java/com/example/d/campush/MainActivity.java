package com.example.d.campush;

import android.content.pm.ActivityInfo;
import android.hardware.Camera;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.alex.livertmppushsdk.FdkAacEncode;
import com.alex.livertmppushsdk.RtmpSessionManager;
import com.alex.livertmppushsdk.SWVideoEncoder;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "Debug";
    private final boolean DEBUG_ENABLE = false;
    private String _myUrl ;
    public SurfaceView _mSurfaceView = null;

//    private final int WIDTH_DEF = 480;
//    private final int HEIGHT_DEF = 640;
    private int WIDTH_DEF = 640;
    private int HEIGHT_DEF = 640;
    private final int FRAMERATE_DEF = 20;
    private final int BITRATE_DEF = 800 * 1000;

    private int _iRecorderBufferSize = 0;
    private final int SAMPLE_RATE_DEF = 22050;
    private final int CHANNEL_NUMBER_DEF = 2;

    private AudioRecord _AudioRecorder = null;
    private byte[] _RecorderBuffer = null;
    private FdkAacEncode _fdkaacEnc = null;
    private int _fdkaacHandle = 0;

    private Camera _mCamera = null;
    private int _iDegrees = 0;
    private int _iCameraCodecType = android.graphics.ImageFormat.NV21;
    private boolean _bStartFlag = false;
    private SWVideoEncoder _swEncH264 = null;

    private Queue<byte[]> _YUVQueue = new LinkedList<byte[]>();
    private Lock _yuvQueueLock = new ReentrantLock();// YUV

    private final static int ID_RTMP_PUSH_START = 100;

    private DataOutputStream _outputStream = null;
    private RtmpSessionManager _rtmpSessionMgr = null;

    private boolean _bIsFront = true;
    private byte[] _yuvEdit = new byte[WIDTH_DEF * HEIGHT_DEF * 3 / 2];

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);


        _myUrl = "rtmp://store.doposoft.cn/live/stream";

        InitAll();
    }
    private void InitAll() {
        WindowManager wm = this.getWindowManager();

        int width = wm.getDefaultDisplay().getWidth();
        int height = wm.getDefaultDisplay().getHeight();
        int iNewWidth = (int) (height * 3.0 / 4.0);

        Log.i(TAG, "  w: " +width + ", h: " + height+",new:"+iNewWidth);

//        WIDTH_DEF = width;
//        HEIGHT_DEF = height;



        _mSurfaceView = (SurfaceView) this.findViewById(R.id.surfaceViewEx);
        _mSurfaceView.getHolder().setFixedSize(HEIGHT_DEF, WIDTH_DEF);
        _mSurfaceView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        _mSurfaceView.getHolder().setKeepScreenOn(true);
        _mSurfaceView.getHolder().addCallback(new SurceCallBack());
        //_mSurfaceView.setLayoutParams(layoutParams);

//        recording = this.findViewById(R.id.recording);

        InitAudioRecord();
        //切换摄像头按钮
//        _SwitchCameraBtn = (Button) findViewById(R.id.SwitchCamerabutton);
//        _SwitchCameraBtn.setOnClickListener(_switchCameraOnClickedEvent);

        //小窗点击切换 并对焦
        _mSurfaceView.setOnClickListener(_switchCameraOnClickedEvent);
        //对焦
//        _mSurfaceView.setOnClickListener(_focusCameraOnClickedEvent);
        //录像
//        _mSurfaceView.setOnTouchListener(new MyClickListenter(new MyClickListenter.MyClickCallBack() {
//            @Override
//            public void oneClick() {
//                //Toast.makeText(RtmpActivity.this,"单击" , Toast.LENGTH_SHORT).show();
//                focuson();
//            }
//
//            @Override
//            public void doubleClick() {
//                // Toast.makeText(RtmpActivity.this,"双击" , Toast.LENGTH_SHORT).show();
//                if(recordingFlag){
//                    recording.setVisibility(View.INVISIBLE);
//                    recordingFlag = false;
//                }else {
//                    //显示  开始录像
//                    recording.setVisibility(View.VISIBLE);
//                    recording.bringToFront();
//                    recordingFlag = true;
//                }
//
//            }
//        }));
        RtmpStartMessage();//开始推流
    }
    private View.OnClickListener _switchCameraOnClickedEvent = new View.OnClickListener() {
        @Override
        public void onClick(View arg0) {
            if (_mCamera == null) {
                return;
            }
            _mCamera.setPreviewCallback(null);
            _mCamera.stopPreview();
            _mCamera.release();
            _mCamera = null;

            if (_bIsFront) {
                _mCamera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK);
            } else {
                _mCamera = Camera.open(Camera.CameraInfo.CAMERA_FACING_FRONT);
            }
            _bIsFront = !_bIsFront;
            InitCamera();
            focuson();
        }
    };
    private final class SurceCallBack implements SurfaceHolder.Callback {
        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            _mCamera.autoFocus(new Camera.AutoFocusCallback() {
                @Override
                public void onAutoFocus(boolean success, Camera camera) {
                    if (success) {
                        InitCamera();
                        camera.cancelAutoFocus();//只有加上了这一句，才会自动对焦。
                    }
                }
            });
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            _iDegrees = getDisplayOritation(getDispalyRotation(), 0);
            if (_mCamera != null) {
                InitCamera();
                return;
            }
            /*
             * 此处华为手机打开有问题
             * */
            /*
             * 再次请求权限 end
             * */

            _mCamera = Camera.open(Camera.CameraInfo.CAMERA_FACING_FRONT);
            InitCamera();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {

        }
    }

    private void InitAudioRecord() {
        _iRecorderBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_DEF,
                AudioFormat.CHANNEL_CONFIGURATION_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        _AudioRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE_DEF, AudioFormat.CHANNEL_CONFIGURATION_STEREO,
                AudioFormat.ENCODING_PCM_16BIT, _iRecorderBufferSize);
        _RecorderBuffer = new byte[_iRecorderBufferSize];

        _fdkaacEnc = new FdkAacEncode();
        _fdkaacHandle = _fdkaacEnc.FdkAacInit(SAMPLE_RATE_DEF, CHANNEL_NUMBER_DEF);
    }
    private int getDisplayOritation(int degrees, int cameraId) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        int result = 0;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;
        } else {
            result = (info.orientation - degrees + 360) % 360;
        }
        return result;
    }
    private int getDispalyRotation() {
        int i = getWindowManager().getDefaultDisplay().getRotation();
        switch (i) {
            case Surface.ROTATION_0:
                return 0;
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
        }
        return 0;
    }
    public void InitCamera() {
        Camera.Parameters p = _mCamera.getParameters();

        Camera.Size prevewSize = p.getPreviewSize();

        List<Camera.Size> PreviewSizeList = p.getSupportedPreviewSizes();
        List<Integer> PreviewFormats = p.getSupportedPreviewFormats();
        for (Camera.Size size : PreviewSizeList) {
            Log.i(TAG, "  w: " + size.width + ", h: " + size.height);
//            WIDTH_DEF = size.width;
//            HEIGHT_DEF = size.height;
        }
        Log.i(TAG, "  surfaceView w: " + WIDTH_DEF + ", h: " + HEIGHT_DEF);
        _mSurfaceView.getHolder().setFixedSize(HEIGHT_DEF, WIDTH_DEF);

        Integer iNV21Flag = 0;
        Integer iYV12Flag = 0;
        for (Integer yuvFormat : PreviewFormats) {
            if (yuvFormat == android.graphics.ImageFormat.YV12) {
                iYV12Flag = android.graphics.ImageFormat.YV12;
            }
            if (yuvFormat == android.graphics.ImageFormat.NV21) {
                iNV21Flag = android.graphics.ImageFormat.NV21;
            }
        }

        if (iNV21Flag != 0) {
            _iCameraCodecType = iNV21Flag;
        } else if (iYV12Flag != 0) {
            _iCameraCodecType = iYV12Flag;
        }
        p.setPreviewSize(HEIGHT_DEF, WIDTH_DEF);
        p.setPreviewFormat(_iCameraCodecType);
        p.setPreviewFrameRate(FRAMERATE_DEF);

        _mCamera.setDisplayOrientation(_iDegrees);
        p.setRotation(_iDegrees);
        _mCamera.setPreviewCallback(_previewCallback);
        _mCamera.setParameters(p);
        try {
            _mCamera.setPreviewDisplay(_mSurfaceView.getHolder());
        } catch (Exception e) {
            return;
        }
        _mCamera.cancelAutoFocus();//只有加上了这一句，才会自动对焦。

        _mCamera.startPreview();
    }


    private void RtmpStartMessage() {
        Message msg = new Message();
        msg.what = ID_RTMP_PUSH_START;
        Bundle b = new Bundle();
        b.putInt("ret", 0);
        msg.setData(b);
        mHandler.sendMessage(msg);
    }

    public Handler mHandler = new Handler() {
        public void handleMessage(android.os.Message msg) {
            Bundle b = msg.getData();
            int ret;
            switch (msg.what) {
                case ID_RTMP_PUSH_START: {
                    Start();
                    break;
                }
            }
        }
    };

    private void Start() {
        if (DEBUG_ENABLE) {
            File saveDir = Environment.getExternalStorageDirectory();
            String strFilename = saveDir + "/aaa.h264";
            try {
                _outputStream = new DataOutputStream(new FileOutputStream(strFilename));
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }
        _rtmpSessionMgr = new RtmpSessionManager();
        //直播地址
        if(_myUrl.isEmpty()){
            showShortMsg("推流地址未获取");
            return;
        }else {
            _rtmpSessionMgr.Start(_myUrl);
        }


        int iFormat = _iCameraCodecType;
        _swEncH264 = new SWVideoEncoder(WIDTH_DEF, HEIGHT_DEF, FRAMERATE_DEF, BITRATE_DEF);
        _swEncH264.start(iFormat);

        _bStartFlag = true;
        // 视频处理线程
        _h264EncoderThread = new Thread(_h264Runnable);
        _h264EncoderThread.setPriority(Thread.MAX_PRIORITY);
        _h264EncoderThread.start();

        //音频 处理 线程
        try {
            _AudioRecorder.startRecording();
            _AacEncoderThread = new Thread(_aacEncoderRunnable);
            _AacEncoderThread.setPriority(Thread.MAX_PRIORITY);
            _AacEncoderThread.start();
        } catch (Exception e) {
            showShortMsg("录音权限未获取");
        }
    }

    private void Stop() {
        _bStartFlag = false;

        _AacEncoderThread.interrupt();
        _h264EncoderThread.interrupt();

        _AudioRecorder.stop();
        _swEncH264.stop();

        _rtmpSessionMgr.Stop();

        _yuvQueueLock.lock();
        _YUVQueue.clear();
        _yuvQueueLock.unlock();

        if (DEBUG_ENABLE) {
            if (_outputStream != null) {
                try {
                    _outputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }


    private Thread _h264EncoderThread = null;
    private Runnable _h264Runnable = new Runnable() {
        @Override
        public void run() {
            while (!_h264EncoderThread.interrupted() && _bStartFlag) {
                int iSize = _YUVQueue.size();
                if (iSize > 0) {
                    _yuvQueueLock.lock();
                    byte[] yuvData = _YUVQueue.poll();
                    if (iSize > 9) {
                        //Log.i(TAG, "###YUV Queue len=" + _YUVQueue.size() + ", YUV length=" + yuvData.length);
                    }

                    _yuvQueueLock.unlock();
                    if (yuvData == null) {
                        continue;
                    }

                    if (_bIsFront) {
                        _yuvEdit = _swEncH264.YUV420pRotate270(yuvData, HEIGHT_DEF, WIDTH_DEF);
                    } else {
                        _yuvEdit = _swEncH264.YUV420pRotate90(yuvData, HEIGHT_DEF, WIDTH_DEF);
                    }
                    byte[] h264Data = _swEncH264.EncoderH264(_yuvEdit);
                    if (h264Data != null) {
                        _rtmpSessionMgr.InsertVideoData(h264Data);
                        if (DEBUG_ENABLE) {
                            try {
                                _outputStream.write(h264Data);
                                int iH264Len = h264Data.length;
                                //Log.i(LOG_TAG, "Encode H264 len="+iH264Len);
                            } catch (IOException e1) {
                                e1.printStackTrace();
                            }
                        }
                    }
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
            _YUVQueue.clear();
        }
    };


    private Runnable _aacEncoderRunnable = new Runnable() {
        @Override
        public void run() {
            DataOutputStream outputStream = null;
            if (DEBUG_ENABLE) {
                File saveDir = Environment.getExternalStorageDirectory();
                String strFilename = saveDir + "/aaa.aac";
                try {
                    outputStream = new DataOutputStream(new FileOutputStream(strFilename));
                } catch (FileNotFoundException e1) {
                    // TODO Auto-generated catch block
                    e1.printStackTrace();
                }
            }

            long lSleepTime = SAMPLE_RATE_DEF * 16 * 2 / _RecorderBuffer.length;

            while (!_AacEncoderThread.interrupted() && _bStartFlag) {
                int iPCMLen = _AudioRecorder.read(_RecorderBuffer, 0, _RecorderBuffer.length); // Fill buffer
                if ((iPCMLen != _AudioRecorder.ERROR_BAD_VALUE) && (iPCMLen != 0)) {
                    if (_fdkaacHandle != 0) {
                        byte[] aacBuffer = _fdkaacEnc.FdkAacEncode(_fdkaacHandle, _RecorderBuffer);
                        if (aacBuffer != null) {
                            long lLen = aacBuffer.length;

                            _rtmpSessionMgr.InsertAudioData(aacBuffer);
                            // Log.i(LOG_TAG, "fdk aac length="+lLen+" from pcm="+iPCMLen);
                            if (DEBUG_ENABLE) {
                                try {
                                    outputStream.write(aacBuffer);
                                } catch (IOException e) {
                                    // TODO Auto-generated catch block
                                    e.printStackTrace();
                                }
                            }
                        }
                    }
                } else {
                    Log.i(TAG, "######fail to get PCM data");
                }
                try {
                    Thread.sleep(lSleepTime / 50);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            //Log.i(TAG, "AAC Encoder Thread ended ......");
        }
    };
    private Thread _AacEncoderThread = null;
    private void showShortMsg(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
    private Camera.PreviewCallback _previewCallback = new Camera.PreviewCallback() {
            @Override
            public void onPreviewFrame(byte[] YUV, Camera currentCamera) {
                    if (!_bStartFlag) {
                        return;
                    }

                    boolean bBackCameraFlag = true;

                    byte[] yuv420 = null;

                    if (_iCameraCodecType == android.graphics.ImageFormat.YV12) {
                        yuv420 = new byte[YUV.length];
                        _swEncH264.swapYV12toI420_Ex(YUV, yuv420, HEIGHT_DEF, WIDTH_DEF);
                    } else if (_iCameraCodecType == android.graphics.ImageFormat.NV21) {
                        yuv420 = _swEncH264.swapNV21toI420(YUV, HEIGHT_DEF, WIDTH_DEF);
                    }

                    if (yuv420 == null) {
                        return;
                    }
                    if (!_bStartFlag) {
                        return;
                    }
                    _yuvQueueLock.lock();
                    if (_YUVQueue.size() > 1) {
                        _YUVQueue.clear();
                    }
                    _YUVQueue.offer(yuv420);
                    _yuvQueueLock.unlock();
            }
    };

    //对焦
    private View.OnClickListener _focusCameraOnClickedEvent = new View.OnClickListener() {
        @Override
        public void onClick(View arg0) {
            if (_mCamera == null) {
                return;
            }
            //_mCamera.setFocusAreas();
            _mCamera.autoFocus(new Camera.AutoFocusCallback() {
                @Override
                public void onAutoFocus(boolean success, Camera camera) {
                    Camera.Parameters params = camera.getParameters();
                }
            });
        }
    };
    //聚焦执行代码
    private void focuson(){
        if (_mCamera == null) {
            return;
        }
        _mCamera.autoFocus(new Camera.AutoFocusCallback() {
            @Override
            public void onAutoFocus(boolean success, Camera camera) {
                Camera.Parameters params = camera.getParameters();
            }
        });
    }
}
