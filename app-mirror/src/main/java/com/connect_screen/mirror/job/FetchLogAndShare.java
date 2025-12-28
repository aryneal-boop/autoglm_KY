package com.connect_screen.mirror.job;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.RemoteException;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;


import com.connect_screen.mirror.State;

import rikka.shizuku.Shizuku;

import java.io.File;
import java.io.IOException;

public class FetchLogAndShare implements Job {
    private final AcquireShizuku acquireShizuku = new AcquireShizuku();
    private boolean userServiceRequested = false;

    private final Context context;

    public FetchLogAndShare(Context context) {
        this.context = context;
    }

    @Override
    public void start() throws YieldException {
        acquireShizuku.start();
        if (!acquireShizuku.acquired) {
            return;
        }
        if (State.userService == null) {
            if (!userServiceRequested) {
                userServiceRequested = true;
                Shizuku.peekUserService(State.userServiceArgs, State.userServiceConnection);
                Shizuku.bindUserService(State.userServiceArgs, State.userServiceConnection);
                State.resumeJobLater(1000);
                throw new YieldException("等待 user service 启动");
            }
            Toast.makeText(State.getContext(), "无法启动 user service 获取日志", Toast.LENGTH_SHORT).show();
            return;
        }

        // 检查存储权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                State.getContext().startActivity(intent);
                Toast.makeText(State.getContext(), "请授予文件访问权限", Toast.LENGTH_LONG).show();
                return;
            }
        }

        try {
            // 获取日志文件路径
            File downloadLogFile = new File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "安卓屏连.log");

            // 删除已存在的日志文件
            if (downloadLogFile.exists()) {
                downloadLogFile.delete();
            }

            // 获取新的日志
            State.userService.fetchLogs();

            // 检查日志文件是否生成
            if (!downloadLogFile.exists()) {
                Toast.makeText(State.getContext(), "未能生成日志文件", Toast.LENGTH_SHORT).show();
                return;
            }

            // 复制到应用缓存目录
            File cacheDir = State.getContext().getCacheDir();
            File cacheCopyFile = new File(cacheDir, "安卓屏连.log");

            // 确保复制成功
            java.nio.file.Files.copy(
                    downloadLogFile.toPath(),
                    cacheCopyFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
            );

            // 创建分享 Intent
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            Uri fileUri = FileProvider.getUriForFile(State.getContext(),
                    State.getContext().getPackageName() + ".provider",
                    cacheCopyFile);
            shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            State.getContext().startActivity(Intent.createChooser(shareIntent, "分享日志文件"));

        } catch (RemoteException | IOException e) {
            Toast.makeText(State.getContext(), "请检查下载目录下是否导出了 '安卓屏连.log' 这个文件", Toast.LENGTH_LONG).show();
            throw new RuntimeException(e);
        }
    }
}
