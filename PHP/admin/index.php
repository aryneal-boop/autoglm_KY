<?php

declare(strict_types=1);

require_once __DIR__ . '/../common.php';

autoglm_require_login();
$cfg = autoglm_load_config();
$latest = $cfg['latest'] ?? [];

$versionName = (string)($latest['versionName'] ?? '');
$versionCode = (int)($latest['versionCode'] ?? 0);
$changelog = (string)($latest['changelog'] ?? '');
$forceUpdate = !empty($latest['forceUpdate']);
$downloadPage = (string)($latest['downloadPage'] ?? '/PHP/download.php');
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
    <title>AutoGLM 更新后台</title>
    <style>
        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial; background:#0b1220; color:#e5e7eb; }
        .wrap { max-width: 860px; margin: 4vh auto; padding: 24px; background:#111827; border:1px solid #1f2937; border-radius: 12px; }
        h1 { font-size: 18px; margin: 0 0 16px; }
        .row { display:flex; gap: 16px; flex-wrap: wrap; }
        .col { flex: 1 1 240px; }
        label { display:block; margin: 12px 0 6px; color:#cbd5e1; }
        input, textarea { width:100%; padding: 10px 12px; border-radius: 10px; border:1px solid #334155; background:#0b1220; color:#e5e7eb; }
        textarea { min-height: 120px; resize: vertical; }
        button { margin-top: 16px; padding: 10px 14px; border-radius: 10px; border:0; background:#22c55e; color:#052e16; font-weight: 700; cursor:pointer; }
        .btn-secondary { background:#334155; color:#e5e7eb; }
        .card { background:#0b1220; border:1px solid #1f2937; border-radius: 12px; padding: 16px; margin-top: 16px; }
        a { color:#93c5fd; }
        .hint { color:#94a3b8; font-size: 12px; }
        .kv { display:flex; gap: 8px; flex-wrap: wrap; color:#cbd5e1; }
        .kv code { background:#111827; padding: 2px 6px; border-radius: 8px; border:1px solid #1f2937; }
    </style>
</head>
<body>
<div class="wrap">
    <div style="display:flex; justify-content:space-between; align-items:center; gap:12px;">
        <h1>应用更新后台</h1>
        <div>
            <a class="btn-secondary" style="text-decoration:none; padding:10px 12px; border-radius:10px; display:inline-block;" href="/PHP/admin/logout.php">退出登录</a>
        </div>
    </div>

    <div class="card">
        <div class="kv">
            <div>当前记录版本：<code><?php echo htmlspecialchars($versionName, ENT_QUOTES, 'UTF-8'); ?></code></div>
            <div>versionCode：<code><?php echo (int)$versionCode; ?></code></div>
            <div>强制更新：<code><?php echo $forceUpdate ? '是' : '否'; ?></code></div>
        </div>
        <div style="margin-top:10px;" class="hint">
            JSON 接口：<code>/PHP/api/update.php</code>（App 通过该接口获取最新版信息）
        </div>
        <div style="margin-top:6px;" class="hint">
            下载页：<code><?php echo htmlspecialchars($downloadPage, ENT_QUOTES, 'UTF-8'); ?></code>
        </div>
        <?php if ($apkUrl !== ''): ?>
            <div style="margin-top:10px;" class="hint">
                当前 APK：<a href="<?php echo htmlspecialchars($apkUrl, ENT_QUOTES, 'UTF-8'); ?>" target="_blank" rel="noreferrer">点击查看/下载</a>
            </div>
        <?php else: ?>
            <div style="margin-top:10px;" class="hint">当前尚未上传 APK。</div>
        <?php endif; ?>
    </div>

    <form class="card" method="post" action="/PHP/admin/save.php" enctype="multipart/form-data">
        <div class="row">
            <div class="col">
                <label>最新版 versionName（例如 1.0.2）</label>
                <input name="versionName" value="<?php echo htmlspecialchars($versionName, ENT_QUOTES, 'UTF-8'); ?>" required>
            </div>
            <div class="col">
                <label>最新版 versionCode（整数，例如 2）</label>
                <input name="versionCode" type="number" min="1" step="1" value="<?php echo (int)$versionCode; ?>" required>
            </div>
            <div class="col">
                <label>是否强制更新</label>
                <div style="margin-top:10px;">
                    <label style="display:flex; gap:8px; align-items:center; margin:0;">
                        <input type="checkbox" name="forceUpdate" value="1" <?php echo $forceUpdate ? 'checked' : ''; ?> style="width:auto;"> 强制更新
                    </label>
                </div>
            </div>
        </div>

        <label>更新说明（changelog）</label>
        <textarea name="changelog"><?php echo htmlspecialchars($changelog, ENT_QUOTES, 'UTF-8'); ?></textarea>

        <label>下载页路径或完整 URL（用户确认更新后会跳转）</label>
        <input name="downloadPage" value="<?php echo htmlspecialchars($downloadPage, ENT_QUOTES, 'UTF-8'); ?>" placeholder="例如 /PHP/download.php 或 https://autoglm.itianyou.cn/PHP/download.php">

        <label>上传最新版 APK（可选）</label>
        <input name="apk" type="file" accept="application/vnd.android.package-archive,.apk">
        <div class="hint">上传后文件会放到 <code>PHP/uploads/</code>。</div>

        <button type="submit">保存</button>
    </form>
</div>
</body>
</html>
