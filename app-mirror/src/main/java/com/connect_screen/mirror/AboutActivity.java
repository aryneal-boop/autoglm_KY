package com.connect_screen.mirror;

import static com.connect_screen.mirror.job.AcquireShizuku.SHIZUKU_PERMISSION_REQUEST_CODE;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.connect_screen.mirror.job.FetchLogAndShare;
import com.connect_screen.mirror.shizuku.ShizukuUtils;

import rikka.shizuku.Shizuku;

public class AboutActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        
        TextView aboutContent = findViewById(R.id.aboutContent);
        aboutContent.setText(
        "本应用使用了开源软件 sunshine（https://github.com/LizardByte/Sunshine） 的源代码。\n\n" +     
        "本应用使用了DisplayLink®的驱动程序(.so文件)用于支持DisplayLink®设备的连接功能。DisplayLink®是Synaptics Incorporated的注册商标。我们仅将其驱动程序用于实现与DisplayLink®设备的兼容性，未对驱动程序进行任何修改。\n\n" +
                "- DisplayLink®驱动程序的所有权利均属于Synaptics Incorporated\n" +
                "- 本应用仅将DisplayLink®驱动用于其预期用途，即支持DisplayLink®设备的连接\n" +
                "- 用户在使用DisplayLink®相关功能时应遵守Synaptics Incorporated的相关许可条款\n" +
                "- 本应用与Synaptics Incorporated没有任何官方关联，不代表或暗示与Synaptics Incorporated存在任何合作关系\n\n" +
                "如有任何与DisplayLink®相关的法律问题，请直接联系Synaptics Incorporated：www.synaptics.com");

        TextView xiaohongshuLink = findViewById(R.id.xiaohongshuLink);
        xiaohongshuLink.setOnClickListener(v -> openUrl("https://www.xiaohongshu.com/user/profile/602cc4c0000000000100be64"));

        TextView bilibiliLink = findViewById(R.id.bilibiliLink);
        bilibiliLink.setOnClickListener(v -> openUrl("https://space.bilibili.com/494726825"));

        TextView douyinLink = findViewById(R.id.douyinLink);
        douyinLink.setOnClickListener(v -> openUrl("https://www.douyin.com/user/MS4wLjABAAAAolJRQWuFI6KZwaBUvPfzDejygnorK2K-CY_6b1OuWQM"));

        TextView youtubeLink = findViewById(R.id.youtubeLink);
        youtubeLink.setOnClickListener(v -> openUrl("https://www.youtube.com/@connect-screen"));

        TextView qqLink = findViewById(R.id.qqLink);
        qqLink.setOnClickListener(v -> joinQQGroup());

        TextView websiteLink = findViewById(R.id.websiteLink);
        websiteLink.setOnClickListener(v -> openUrl("https://connect-screen.com"));

        TextView versionText = findViewById(R.id.versionText);
        try {
            String versionName = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            String androidVersion = android.os.Build.VERSION.RELEASE;
            versionText.setText("版本：" + versionName + " (Android系统 " + androidVersion + ")");
        } catch (Exception e) {
            versionText.setText("版本：未知");
        }

        GestureDetector gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (!ShizukuUtils.hasShizukuStarted()) {
                    State.log("shizuku not started");
                    return false;
                }
                if (!ShizukuUtils.hasPermission()) {
                    State.log("ask shizuku permission");
                    Toast.makeText(AboutActivity.this, "导出故障日志需要 shizuku 权限", Toast.LENGTH_SHORT).show();
                    Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE);
                    return false;
                }
                State.startNewJob(new FetchLogAndShare(AboutActivity.this));
                return true;
            }

            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }
        });

        View header = findViewById(R.id.header);
        header.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return true;
        });
    }

    private void openUrl(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        startActivity(intent);
    }

    public void joinQQGroup() {
        String key = "ngIy53SQRlz6tAO0UEkmALBjvGKkDYrq";
        Intent intent = new Intent();
        intent.setData(Uri.parse("mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D" + key));
        try {
            startActivity(intent);
        } catch (Exception e) {
            openUrl("https://connect-screen.com");
        }
    }
} 