<?php

declare(strict_types=1);

require_once __DIR__ . '/../common.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');

$cfg = autoglm_load_config();
$latest = $cfg['latest'] ?? [];

$apkFile = (string)($latest['apkFile'] ?? '');
$apkUrl = '';
if ($apkFile !== '') {
    $apkUrl = autoglm_base_url() . '/PHP/uploads/' . rawurlencode(basename($apkFile));
}

$downloadPage = (string)($latest['downloadPage'] ?? '/PHP/download.php');
$downloadUrl = $downloadPage;
if ($downloadUrl !== '' && !preg_match('#^https?://#i', $downloadUrl)) {
    $downloadUrl = autoglm_base_url() . $downloadUrl;
}

// 统一输出为 http（避免客户端强制使用 https）。
if ($downloadUrl !== '') {
    $downloadUrl = preg_replace('#^https://#i', 'http://', $downloadUrl);
}

$out = [
    'app' => 'autoglm',
    'latest' => [
        'versionName' => (string)($latest['versionName'] ?? ''),
        'versionCode' => (int)($latest['versionCode'] ?? 0),
        'changelog' => (string)($latest['changelog'] ?? ''),
        'forceUpdate' => !empty($latest['forceUpdate']),
        'releasedAt' => (string)($latest['releasedAt'] ?? ''),
        'downloadPage' => $downloadUrl,
        'apkUrl' => $apkUrl,
    ],
];

echo json_encode($out, JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT);
