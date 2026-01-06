<?php

declare(strict_types=1);

require_once __DIR__ . '/common.php';

$cfg = autoglm_load_config();
$latest = $cfg['latest'] ?? [];

$versionName = (string)($latest['versionName'] ?? '');
$versionCode = (int)($latest['versionCode'] ?? 0);
$changelog = (string)($latest['changelog'] ?? '');
$apkFile = (string)($latest['apkFile'] ?? '');

$apkUrl = '';
if ($apkFile !== '') {
    $apkUrl = autoglm_base_url() . '/PHP/uploads/' . rawurlencode(basename($apkFile));
}

?>
<!doctype html>
<html lang="zh-CN">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>AutoGLM 下载</title>
    <style>
        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial; background:#0b1220; color:#e5e7eb; }
        .wrap { max-width: 860px; margin: 4vh auto; padding: 24px; background:#111827; border:1px solid #1f2937; border-radius: 12px; }
        h1 { font-size: 18px; margin: 0 0 10px; }
        .meta { color:#cbd5e1; }
        .card { background:#0b1220; border:1px solid #1f2937; border-radius: 12px; padding: 16px; margin-top: 16px; }
        .btn { display:inline-block; padding: 10px 14px; border-radius: 10px; background:#22c55e; color:#052e16; font-weight:700; text-decoration:none; }
        .btn-secondary { background:#334155; color:#e5e7eb; }
        pre { white-space: pre-wrap; word-break: break-word; background:#0b1220; border:1px solid #1f2937; padding: 12px; border-radius: 12px; color:#e5e7eb; }
        .hint { color:#94a3b8; font-size: 12px; }
    </style>
</head>
<body>
<div class="wrap">
    <h1>下载 AutoGLM</h1>
    <div class="meta">最新版本：<?php echo htmlspecialchars($versionName, ENT_QUOTES, 'UTF-8'); ?>（<?php echo (int)$versionCode; ?>）</div>

    <div class="card">
        <?php if ($apkUrl !== ''): ?>
            <a class="btn" href="<?php echo htmlspecialchars($apkUrl, ENT_QUOTES, 'UTF-8'); ?>">下载 APK</a>
        <?php else: ?>
            <div>管理员尚未上传 APK，请稍后再试。</div>
        <?php endif; ?>
        <a class="btn btn-secondary" style="margin-left:10px;" href="/PHP/api/update.php">查看 JSON</a>
        <div class="hint" style="margin-top:10px;">
            Android 安装时如遇到系统拦截，请在系统设置中允许“安装未知来源应用”。
        </div>
    </div>

    <div class="card">
        <div style="margin-bottom:8px;">更新说明：</div>
        <pre><?php echo htmlspecialchars($changelog, ENT_QUOTES, 'UTF-8'); ?></pre>
    </div>

    <div class="hint" style="margin-top:14px;">
        管理后台：<a href="/PHP/admin/login.php" style="color:#93c5fd;">/PHP/admin/login.php</a>
    </div>
</div>
</body>
</html>
