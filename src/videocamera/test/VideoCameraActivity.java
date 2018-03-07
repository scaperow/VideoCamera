package videocamera.test;

//import java.io.DataInputStream;  
import java.io.File;  
import java.io.IOException;  
import java.io.InputStream;  
import java.io.RandomAccessFile;  
import android.app.Activity;  
import android.content.Context;  
import android.os.Bundle;  
import android.graphics.PixelFormat;  
import android.media.MediaRecorder;  
import android.net.LocalServerSocket;  
import android.net.LocalSocket;  
import android.net.LocalSocketAddress;  
import android.util.Log;  
import android.view.SurfaceHolder;  
import android.view.SurfaceView;  
import android.view.View;  
import android.view.Window;  
import android.view.WindowManager;
import android.widget.Toast;

import java.util.TimerTask;
import java.util.Timer;
import android.os.Handler;
import android.os.Message;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.DatagramPacket;
import java.net.SocketException;
import java.net.UnknownHostException;

public class VideoCameraActivity extends Activity implements  
SurfaceHolder.Callback, MediaRecorder.OnErrorListener,  
MediaRecorder.OnInfoListener {  
	private static final String TAG = "VideoCamera";  
	private static final int FRAME_RATE = 15;
	private static final int RTP_INCREACEMENT = 90000 / FRAME_RATE;	//90KHz is defined in RFC2190
	LocalSocket receiver, sender;  
	LocalServerSocket lss;  
	private MediaRecorder mMediaRecorder = null;  
	boolean m_bMediaRecorderRecording = false;	//flag for video recording  
	private SurfaceView mSurfaceView = null;  
	private SurfaceHolder mSurfaceHolder = null;  
	Thread m_threadForCapture;  
	Context mContext = this;  
	RandomAccessFile raf = null;
	
	//content that xin added
	private int intCapturedFrameCount = 0;
	private enum stateMachineOfFrameReceiving {FRAMEHEAD_FOUND, FRAMEHEAD_NOTFOUND};
	private static final byte RTP_POCKET_SSRC[] = {(byte)0xaf, (byte)0x3f, (byte)0x88, (byte)0x88};
	private short RTP_sn = 0x1111;
	private int RTP_timestamp = 0x0;
	private int offsetInRTP_pocket_buffer = 0;
	private boolean threadCanStop = false;

	//timer for statistics
	private final Timer m_timer = new Timer();
	private TimerTask m_timerTask;
	Handler handler = new Handler()
	{
	   @Override
	   public void handleMessage(Message msg) {
			//要做的事情
		  // Log.d(TAG, "intCapturedFrameCount :" + String.valueOf(intCapturedFrameCount)); 
		   intCapturedFrameCount = 0;
		   super.handleMessage(msg);
	   }
	  
	};

	//****************************************
	//
	//****************************************
	@Override  
	public void onCreate(Bundle savedInstanceState) {  
		super.onCreate(savedInstanceState);  
		getWindow().setFormat(PixelFormat.TRANSLUCENT);  
		requestWindowFeature(Window.FEATURE_NO_TITLE);  
		getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,  
		        WindowManager.LayoutParams.FLAG_FULLSCREEN);  
		setContentView(R.layout.main);  
		mSurfaceView = (SurfaceView) this.findViewById(R.id.surface_camera);//set a view to display video  
		SurfaceHolder holder = mSurfaceView.getHolder();  
		holder.addCallback(this);  
		holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);  
		mSurfaceView.setVisibility(View.VISIBLE); 
		
		//setup local sockets
		
		receiver = new LocalSocket();  
		try {  
		    lss = new LocalServerSocket("VideoCamera");  
		    receiver.connect(new LocalSocketAddress("VideoCamera"));  
		    receiver.setReceiveBufferSize(500000);  
		    receiver.setSendBufferSize(500000);  
		    sender = lss.accept();  
		    sender.setReceiveBufferSize(500000);  
		    sender.setSendBufferSize(500000);  
		} catch (IOException e) {  
		    finish();  
		    return;  
		}  
		
		m_timerTask = new TimerTask() {
		    @Override
		    public void run() {
		     Message message = new Message();
		     message.what = 1;
		     handler.sendMessage(message);
		    }
		   };

	} 
	
	//****************************************
	//
	//****************************************
	@Override  
	public void onStart() {  
		super.onStart();  
	}  
	
	//****************************************
	//
	//****************************************
	@Override  
	public void onResume() {  
		super.onResume();  
	}  
	
	//****************************************
	//	TODO: This routine need to be took care of elaborately later
	//****************************************
	@Override/*stop recording and close socket*/  
	public void onPause() {  
		super.onPause();  
		if (m_bMediaRecorderRecording) {  
		    stopVideoRecording();  
		    try {  
		        lss.close();  
		        receiver.close();  
		        sender.close();  
		    } catch (IOException e) {  
		        e.printStackTrace();  
		    }  
		}  
		finish();  
	}  
	
	//****************************************
	//	Stop Recording
	//****************************************
	private void stopVideoRecording() {  
		Log.d(TAG, "stopVideoRecording");
		
		if (m_bMediaRecorderRecording || mMediaRecorder != null) { 
			
			threadCanStop = true;
			
		    //wait for a while
	        try {  
	            Thread.currentThread().sleep(400); 
	        } catch (InterruptedException e1) {  
	        	e1.printStackTrace();  
	        }
			
		    if (m_threadForCapture != null)  
		        m_threadForCapture.interrupt();
		    
		    //wait for a while
	        try {  
	            Thread.currentThread().sleep(400); 
	        } catch (InterruptedException e1) {  
	        	e1.printStackTrace();  
	        }
	       
		    try {  
		    	raf.close();  
		    } catch (IOException e) {  
		        e.printStackTrace();  
		    }  
		    
		    
		    releaseMediaRecorder();  
		} 
		
	}  

	//****************************************
	//	Begin recording
	//****************************************
	private void startVideoRecording() {  
		Log.d(TAG, "startVideoRecording");  
		(m_threadForCapture = new Thread() {
			
			// start a new thread for capturing
			
		    public void run() {  
		        final int READ_SIZE = 1024;	//bytes count for every reading from 'inputStreamForReceive'
		        final int BUFFER_SIZE_RECEIVE = READ_SIZE * 128;
		        final int BUFFER_SIZE_RTP = READ_SIZE * 20;
		        stateMachineOfFrameReceiving STM_Recv = stateMachineOfFrameReceiving.FRAMEHEAD_NOTFOUND;
		        int i;
		        byte[] PSC_checkArr = new byte[3];
		        byte[] RTP_pocket_buffer = new byte[BUFFER_SIZE_RTP];
		        byte[] bufferForReceive = new byte[BUFFER_SIZE_RECEIVE]; 	//64K
		        int num = 0;  
		        InputStream inputStreamForReceive = null;

		        //udp
		        final int RTP_PORT_SENDING = 8000;
		        final int RTP_PORT_DEST = 8000;
		        DatagramSocket socketSendRPT = null;
		        DatagramPacket RtpPocket = null;
		        InetAddress PC_IpAddress = null;
		        try { 
		        	PC_IpAddress = InetAddress.getByName("sc-host");
		        } 
		        catch (UnknownHostException e){
		        	e.printStackTrace();
		        } 
		        
		        try{
		        	socketSendRPT = new DatagramSocket(RTP_PORT_SENDING);
		        }
		        catch (SocketException e){
		        	e.printStackTrace();
		        }
  
		       
		        
		      //get the data stream from mediaRecorder
		        try {  
		            inputStreamForReceive = receiver.getInputStream();	
		        } catch (IOException e1) {  
		        	Log.d("---------------------","EXCEPTION1");
		            return;  
		        } 
		        

		        
		        int offsetInBufferForReceive = 0;
 		        m_timer.schedule(m_timerTask, 1000, 1000);	//timer for statistics
      
 		        STM_Recv = stateMachineOfFrameReceiving.FRAMEHEAD_NOTFOUND;
 		        PSC_checkArr[0] = (byte)0xFF;
				PSC_checkArr[1] = (byte)0xFF;
				PSC_checkArr[2] = (byte)0xFF;
 		        while (true && !threadCanStop) { 
			        //1. read data from receive stream until to the end
 		        	offsetInBufferForReceive = 0;
			        do {  
			            try {  
			                num = inputStreamForReceive.read(bufferForReceive, offsetInBufferForReceive, READ_SIZE);//copy data to buffer
			                if(num < 0){	// there's nothing in there
			                	//wait for a while
						        try {  
						            Thread.currentThread().sleep(5); 
						        } catch (InterruptedException e1) {  
						        	e1.printStackTrace();  
						        }
						        break;
			                }
			                
			                offsetInBufferForReceive += num; //offsetInBufferForReceive points to next position can be wrote
			                if (num < READ_SIZE || offsetInBufferForReceive == BUFFER_SIZE_RECEIVE) {  //indicating the end of this reading
			                    break;  																 // or bufferForReceive is full
			                }  
			            } catch (IOException e) {  
			            	Log.d("---------------------","EXCEPTION");
			                break;  
			            }  
			        }while(false);//do
			        
			        //2. find Picture Start Code (PSC) (22 bits) in bufferForReceive
			        for(i=0; i< offsetInBufferForReceive; i++){
			        	PSC_checkArr[0] = PSC_checkArr[1];
			        	PSC_checkArr[1] = PSC_checkArr[2];
			        	PSC_checkArr[2] = bufferForReceive[i];
			        	
			        	//see if got the PSC
			        	if(PSC_checkArr[0] == 0 && PSC_checkArr[1] == 0 && (PSC_checkArr[2] & (byte)0xFC) == (byte)0x80)
			        	{
			        		//found the PSC
			        		if(STM_Recv == stateMachineOfFrameReceiving.FRAMEHEAD_NOTFOUND)
			        		{
			        			STM_Recv = stateMachineOfFrameReceiving.FRAMEHEAD_FOUND;
			        			//copy current byte to packet buffer
			        			intializeRTP_PocketBuffer(RTP_pocket_buffer);
			        			RTP_pocket_buffer[offsetInRTP_pocket_buffer] = bufferForReceive[i];
			        			offsetInRTP_pocket_buffer++;
			        		}
			        		else if(STM_Recv == stateMachineOfFrameReceiving.FRAMEHEAD_FOUND)
			        		{
			        			//delete two zeros in the end of the buffer
			        			offsetInRTP_pocket_buffer -= 2;

			        			//TODO:3. send the packet, and reset the buffer
			        			RtpPocket = new DatagramPacket(RTP_pocket_buffer, offsetInRTP_pocket_buffer, PC_IpAddress, RTP_PORT_DEST);  
			        			try {
			        				socketSendRPT.send(RtpPocket);
			        				Log.v(TAG, "Sent bytes: " + String.valueOf(offsetInRTP_pocket_buffer));  
			        			} catch (IOException e) {
			        				e.printStackTrace();
								}
			        			
			        			/*
						        try { 
						        	raf.write(RTP_pocket_buffer, 0, offsetInRTP_pocket_buffer);  
						        	//raf.close();
						        } catch (IOException e1) {  
						            e1.printStackTrace();  
						        }
						        */
						        
						        intCapturedFrameCount++;	//for statistics
			        			//copy current byte to packet buffer
			        			intializeRTP_PocketBuffer(RTP_pocket_buffer);
			        			RTP_pocket_buffer[offsetInRTP_pocket_buffer] = bufferForReceive[i];
			        			offsetInRTP_pocket_buffer++;
			        			
			        		}
	
			        	}
			        	else//if NOT got the PSC
			        	{
			        		if(STM_Recv == stateMachineOfFrameReceiving.FRAMEHEAD_NOTFOUND)
			        		{
			        			continue;
			        		}
			        		else if(STM_Recv == stateMachineOfFrameReceiving.FRAMEHEAD_FOUND)
			        		{
			        			//copy current byte to packet buffer
			        			RTP_pocket_buffer[offsetInRTP_pocket_buffer] = bufferForReceive[i];
			        			offsetInRTP_pocket_buffer++;
			        		}
			        	}//if(PSC_checkArr[0] == 0 && PSC_checkArr[1] == 0 && (PSC_checkArr[2] & (byte)0xFC) == (byte)0x80)
			        	
			        	//4. if RTP_pocket_buffer is full then discard all the bytes in it
			        	if(offsetInRTP_pocket_buffer >= BUFFER_SIZE_RTP)
			        	{
			        		STM_Recv = stateMachineOfFrameReceiving.FRAMEHEAD_NOTFOUND;
			        		offsetInRTP_pocket_buffer = 0;
			        	}
			        	
			        }//for(i=0; i< offsetInBufferForReceive; i++)
			        
			        
		        }// while (true && !threadCanStop)
 		        
 		        m_timer.cancel();
		        
		        releaseMediaRecorder();
		        
		    }  
		}).start();//start new thread  
	} 
	
	//****************************************
	// Initialize RTP pocket Buffer
	//****************************************
	private void intializeRTP_PocketBuffer(byte[] bufferRTP)
	{
		
		bufferRTP[0] = (byte)0x80;
		bufferRTP[1] = (byte)0xA2;
		
		//sn
		bufferRTP[2] = (byte)(RTP_sn >> 8);
		bufferRTP[3] = (byte)RTP_sn;
		RTP_sn++;
		
		//timestamp
		bufferRTP[4] = (byte)(RTP_timestamp >> 24);
		bufferRTP[5] = (byte)(RTP_timestamp >> 16);
		bufferRTP[6] = (byte)(RTP_timestamp >> 8);
		bufferRTP[7] = (byte)RTP_timestamp;
		RTP_timestamp += RTP_INCREACEMENT;
		
		//ssrc
		bufferRTP[8] = RTP_POCKET_SSRC[0];
		bufferRTP[9] = RTP_POCKET_SSRC[1];
		bufferRTP[10] = RTP_POCKET_SSRC[2];
		bufferRTP[11] = RTP_POCKET_SSRC[3];
		
		//rtp's beginning
		bufferRTP[12] = 0;
		bufferRTP[13] = 0;
		
		offsetInRTP_pocket_buffer = 14;	//points to next null position

	}
	
	//****************************************
	// Initialize MediaRecorder
	//****************************************
	private boolean initializeVideo() {  
		if (mSurfaceHolder==null)  
		    return false;  
		m_bMediaRecorderRecording = true;  
		if (mMediaRecorder == null)  
		    mMediaRecorder = new MediaRecorder();//get MediaRecorder for video capture 
		else  
		    mMediaRecorder.reset();
		/*set MediaRecorder parameters*/
		mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);  
		mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);//set video output format  
		mMediaRecorder.setVideoFrameRate(FRAME_RATE);  
		mMediaRecorder.setVideoSize(352, 288);  
		//mMediaRecorder.setVideoSize(176, 144);  
		mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H263);//set Encoder  
		mMediaRecorder.setPreviewDisplay(mSurfaceHolder.getSurface());//set preview  
		mMediaRecorder.setMaxDuration(0);  
		mMediaRecorder.setMaxFileSize(0);
		mMediaRecorder.setOutputFile(sender.getFileDescriptor());//set video output to socket, which is a
															 //necessary way to transfer real-time video
		try {  
		    mMediaRecorder.setOnInfoListener(this);  
		    mMediaRecorder.setOnErrorListener(this);  
		    mMediaRecorder.prepare();  
		    mMediaRecorder.start();  	//start the mediarecorder
		} catch (IOException exception) {  
		    releaseMediaRecorder();  
		    finish();  
		    return false;  
		}  
		return true;  
	}  

	//****************************************
	//
	//****************************************
	private void releaseMediaRecorder() {  
		Log.v(TAG, "Releasing media recorder.");  
		if (mMediaRecorder != null) {  
		    if (m_bMediaRecorderRecording) {  
		        try {  
		            mMediaRecorder.setOnErrorListener(null);  
		            mMediaRecorder.setOnInfoListener(null);  
		            mMediaRecorder.stop();  
		        } catch (RuntimeException e) {  
		            Log.e(TAG, "stop fail: " + e.getMessage());  
		        }  
		        m_bMediaRecorderRecording = false;  
		    }  
		    mMediaRecorder.stop();
		    //wait for a while
	        try {  
	            Thread.currentThread().sleep(100); 
	        } catch (InterruptedException e1) {  
	        	e1.printStackTrace();  
	        }
		    mMediaRecorder.reset();  
		    mMediaRecorder.release();  
		    mMediaRecorder = null;  
		}  
	}  

	//****************************************
	//
	//****************************************
	public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {  
		Log.d(TAG, "surfaceChanged");  
		mSurfaceHolder = holder;  
		if (!m_bMediaRecorderRecording) {  
		    initializeVideo();  
		    startVideoRecording();  
		}  
	}  

	//****************************************
	//
	//****************************************
	public void surfaceCreated(SurfaceHolder holder) {  
	Log.d(TAG, "surfaceCreated");  
	mSurfaceHolder = holder;  
	}  
 
	//****************************************
	//
	//****************************************
	public void surfaceDestroyed(SurfaceHolder holder) {  
	Log.d(TAG, "surfaceDestroyed");  
	mSurfaceHolder = null;  
	}  

	//****************************************
	//
	//****************************************
	public void onInfo(MediaRecorder mr, int what, int extra) {  
		switch (what) {  
		case MediaRecorder.MEDIA_RECORDER_INFO_UNKNOWN:  
		    Log.d(TAG, "MEDIA_RECORDER_INFO_UNKNOWN");  
		    break;  
		case MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED:  
		    Log.d(TAG, "MEDIA_RECORDER_INFO_MAX_DURATION_REACHED");  
		    break;  
		case MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED:  
		    Log.d(TAG, "MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED");  
		    break;  
		}  
	}  

	//****************************************
	//
	//****************************************
	public void onError(MediaRecorder mr, int what, int extra) {  
		if (what == MediaRecorder.MEDIA_RECORDER_ERROR_UNKNOWN) {  
		    Log.d(TAG, "MEDIA_RECORDER_ERROR_UNKNOWN");  
		    finish();  
		}  
	}  
} 