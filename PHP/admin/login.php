<?php

declare(strict_types=1);

require_once __DIR__ . '/../common.php';

autoglm_start_session();

$cfg = autoglm_load_config();
$error = '';

if (($_SERVER['REQUEST_METHOD'] ?? '') === 'POST') {
    $username = trim((string)($_POST['username'] ?? ''));
    $password = (string)($_POST['password'] ?? '');

    $admin = $cfg['admin'] ?? [];
    $okUser = ($admin['username'] ?? '') === $username;
    $hash = (string)($admin['password_hash'] ?? '');

    if ($okUser && $hash !== '' && password_verify($password, $hash)) {
        $_SESSION['autoglm_admin_logged_in'] = 1;
        header('Location: /PHP/admin/index.php');
        exit;
    }

    $error = '账号或密码错误';
}

?>
<!doctype html>
<html lang="zh-CN">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>AutoGLM 后台登录</title>
    <style>
        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial; background:#0b1220; color:#e5e7eb; }
        .wrap { max-width: 420px; margin: 8vh auto; padding: 24px; background:#111827; border:1px solid #1f2937; border-radius: 12px; }
        h1 { font-size: 18px; margin: 0 0 16px; }
        label { display:block; margin: 12px 0 6px; color:#cbd5e1; }
        input { width:100%; padding: 10px 12px; border-radius: 10px; border:1px solid #334155; background:#0b1220; color:#e5e7eb; }
        button { margin-top: 16px; width:100%; padding: 10px 12px; border-radius: 10px; border:0; background:#22c55e; color:#052e16; font-weight: 700; cursor:pointer; }
        .error { margin-top: 12px; color:#fca5a5; }
        .hint { margin-top: 12px; color:#94a3b8; font-size: 12px; }
        a { color:#93c5fd; }
    </style>
</head>
<body>
<div class="wrap">
    <h1>管理员登录</h1>
    <form method="post" action="">
        <label>账号</label>
        <input name="username" autocomplete="username" required>

        <label>密码</label>
        <input name="password" type="password" autocomplete="current-password" required>

        <button type="submit">登录</button>
        <?php if ($error !== ''): ?>
            <div class="error"><?php echo htmlspecialchars($error, ENT_QUOTES, 'UTF-8'); ?></div>
        <?php endif; ?>
        <div class="hint">
            默认账号：admin，默认密码：123456（可在服务器上删除 <code>PHP/data/config.json</code> 以重置）。
        </div>
    </form>
</div>
</body>
</html>
