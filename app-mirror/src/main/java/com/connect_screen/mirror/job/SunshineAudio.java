package com.connect_screen.mirror.job;

import static com.connect_screen.mirror.MirrorMainActivity.REQUEST_RECORD_AUDIO_PERMISSION;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;

import androidx.core.app.ActivityCompat;

import com.connect_screen.mirror.MirrorMainActivity;
import com.connect_screen.mirror.Pref;
import com.connect_screen.mirror.State;
import com.connect_screen.mirror.shizuku.ServiceUtils;

public class SunshineAudio {
    private static boolean audioPermissionRequested;
    private static boolean isMuted = false;
    private static AudioManager.OnAudioFocusChangeListener volumeChangeListener;
    public static boolean sendAudio(Context context, int packetDuration) throws YieldException {
        if (shouldUseShizukuAudio()) {
            int framesPerPacket = (int) (48000 * packetDuration / 1000.0f);
            AudioRecordProxy audioRecordProxy = new AudioRecordProxy();
            if (!startRecording()) {
                State.log("启动录音失败");
                return true;
            }
            SunshineServer.startAudioRecording(audioRecordProxy, framesPerPacket);
        } else {
            if (sendAudioUseNormalPermission(context, packetDuration)) {
                return true;
            }
            // 检查音频设置权限
            if (context.checkSelfPermission(android.Manifest.permission.MODIFY_AUDIO_SETTINGS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                State.log("没有音频控制权限，无法静音");
            }
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
            if (audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
                isMuted = true;
                State.log("应客户端的请求对手机静音");
                // 注册音量变化监听器
                registerVolumeChangeListener(context, audioManager);
            } else {
                State.log("静音设置未成功");
            }
            return false;
        }
        return false;
    }

    // 添加注册音量变化监听器的方法
    private static void registerVolumeChangeListener(Context context, AudioManager audioManager) {

        // 创建音频焦点变化监听器
        volumeChangeListener = focusChange -> {
            // 如果还在投屏且应该保持静音状态，检查并重新设置静音
            if (State.mirrorVirtualDisplay != null && isMuted) {
                checkAndRestoreMute();
            }
        };

        // 请求音频焦点以便接收音频变化事件
        audioManager.requestAudioFocus(volumeChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);

        // 创建内容观察者监听音量变化
        context.getContentResolver().registerContentObserver(
                android.provider.Settings.System.CONTENT_URI,
                true,
                new android.database.ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override
                    public void onChange(boolean selfChange) {
                        super.onChange(selfChange);
                        // 如果还在投屏且应该保持静音状态，检查并重新设置静音
                        if (State.mirrorVirtualDisplay != null && isMuted) {
                            checkAndRestoreMute();
                        }
                    }
                }
        );
    }

    // 检查并恢复静音状态
    private static void checkAndRestoreMute() {
        Context context = State.getContext();
        if (context == null) {
            return;
        }
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (!audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
            State.log("检测到音量变化，重新设置静音");
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
        }
    }

    private static boolean shouldUseShizukuAudio() {
        if (Pref.getDisableRemoteSubmix()) {
            return false;
        }
        return State.userService != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S;
    }

    private static boolean startRecording() {
        try {
            return State.userService.startRecordingAudio();
        } catch (RemoteException e) {
            return false;
        }
    }

    private static boolean sendAudioUseNormalPermission(Context context, int packetDuration) throws YieldException {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            State.log("安卓版本太低，无法录音");
            return false;
        }
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            // 配置音频捕获参数
            int sampleRate = 48000; // 与您的Opus配置匹配
            int channelConfig = AudioFormat.CHANNEL_IN_STEREO;
            int audioEncoding = AudioFormat.ENCODING_PCM_FLOAT;
            int bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding) * 2;

            // 计算每个数据包的帧数 (每个通道的样本数)
            // packetDuration 是毫秒，所以需要除以1000转换为秒
            int framesPerPacket = (int) (sampleRate * packetDuration / 1000.0f);
            AudioFormat audioFormat = new AudioFormat.Builder()
                    .setEncoding(audioEncoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build();
            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(State.getMediaProjection())
                    .excludeUsage(AudioAttributes.USAGE_ALARM)
                    .build();
            AudioRecord audioRecord = new AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .build();
            audioRecord.startRecording();

            // 将 AudioRecord 传递给 SunshineServer 进行处理
            SunshineServer.startAudioRecording(audioRecord, framesPerPacket);

        } else {
            if (audioPermissionRequested) {
                State.log("因为未授予录音权限，跳过任务");
                return true;
            }
            audioPermissionRequested = true;
            MirrorMainActivity activity = State.getCurrentActivity();
            if (activity == null) {
                return true;
            }
            ActivityCompat.requestPermissions(activity,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_AUDIO_PERMISSION);
            throw new YieldException("等待录音权限授权");
        }
        return false;
    }

    public static void restoreVolume(Context context) {
        if (State.userService != null) {
            try {
                State.userService.stopRecordingAudio();
            } catch (RemoteException e) {
                // ignore
            }
        }
        if (isMuted && context != null) {
            State.log("恢复音量");
            isMuted = false;
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);

            // 取消注册音量变化监听器
            if (volumeChangeListener != null) {
                audioManager.abandonAudioFocus(volumeChangeListener);
                volumeChangeListener = null;
            }

        }
    }
}
