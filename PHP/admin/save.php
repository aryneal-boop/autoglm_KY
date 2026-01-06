<?php

declare(strict_types=1);

require_once __DIR__ . '/../common.php';

autoglm_require_login();

$cfg = autoglm_load_config();

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    header('Location: /PHP/admin/index.php');
    exit;
}

$versionName = trim((string)($_POST['versionName'] ?? ''));
$versionCode = (int)($_POST['versionCode'] ?? 0);
$changelog = trim((string)($_POST['changelog'] ?? ''));
$forceUpdate = !empty($_POST['forceUpdate']);
$downloadPage = trim((string)($_POST['downloadPage'] ?? ''));

if ($versionName === '' || $versionCode <= 0) {
    header('Location: /PHP/admin/index.php');
    exit;
}

if ($downloadPage === '') {
    $downloadPage = '/PHP/download.php';
}

$apkFile = (string)(($cfg['latest']['apkFile'] ?? ''));

autoglm_ensure_dirs();

if (!empty($_FILES['apk']) && is_array($_FILES['apk'])) {
    $f = $_FILES['apk'];
    $err = (int)($f['error'] ?? UPLOAD_ERR_NO_FILE);

    if ($err === UPLOAD_ERR_OK) {
        $tmp = (string)($f['tmp_name'] ?? '');
        $name = (string)($f['name'] ?? '');
        $ext = strtolower(pathinfo($name, PATHINFO_EXTENSION));

        if ($tmp !== '' && is_uploaded_file($tmp) && $ext === 'apk') {
            $safe = preg_replace('/[^a-zA-Z0-9_\-\.]+/', '_', basename($name));
            $targetName = date('Ymd_His') . '_' . $safe;
            $targetPath = autoglm_upload_dir() . DIRECTORY_SEPARATOR . $targetName;

            if (@move_uploaded_file($tmp, $targetPath)) {
                $apkFile = $targetName;
            }
        }
    }
}

$cfg['latest'] = [
    'versionName' => $versionName,
    'versionCode' => $versionCode,
    'changelog' => $changelog,
    'forceUpdate' => $forceUpdate,
    'apkFile' => $apkFile,
    'downloadPage' => $downloadPage,
    'releasedAt' => date('c'),
];

autoglm_save_config($cfg);

header('Location: /PHP/admin/index.php');
exit;
