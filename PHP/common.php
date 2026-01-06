<?php

declare(strict_types=1);

function autoglm_data_dir(): string {
    return __DIR__ . DIRECTORY_SEPARATOR . 'data';
}

function autoglm_upload_dir(): string {
    return __DIR__ . DIRECTORY_SEPARATOR . 'uploads';
}

function autoglm_config_path(): string {
    return autoglm_data_dir() . DIRECTORY_SEPARATOR . 'config.json';
}

function autoglm_ensure_dirs(): void {
    $dataDir = autoglm_data_dir();
    $uploadDir = autoglm_upload_dir();

    if (!is_dir($dataDir)) {
        @mkdir($dataDir, 0755, true);
    }
    if (!is_dir($uploadDir)) {
        @mkdir($uploadDir, 0755, true);
    }
}

function autoglm_default_config(): array {
    return [
        'admin' => [
            'username' => 'admin',
            'password_hash' => password_hash('123456', PASSWORD_DEFAULT),
        ],
        'latest' => [
            'versionName' => '1.0',
            'versionCode' => 1,
            'changelog' => '',
            'forceUpdate' => false,
            'apkFile' => '',
            'downloadPage' => '/PHP/download.php',
            'releasedAt' => date('c'),
        ],
    ];
}

function autoglm_load_config(): array {
    autoglm_ensure_dirs();

    $path = autoglm_config_path();
    if (!is_file($path)) {
        $cfg = autoglm_default_config();
        autoglm_save_config($cfg);
        return $cfg;
    }

    $raw = @file_get_contents($path);
    if ($raw === false) {
        $cfg = autoglm_default_config();
        autoglm_save_config($cfg);
        return $cfg;
    }

    $cfg = json_decode($raw, true);
    if (!is_array($cfg)) {
        $cfg = autoglm_default_config();
        autoglm_save_config($cfg);
        return $cfg;
    }

    return $cfg;
}

function autoglm_save_config(array $cfg): void {
    autoglm_ensure_dirs();

    $path = autoglm_config_path();
    $json = json_encode($cfg, JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT);
    @file_put_contents($path, $json === false ? "{}" : $json);
}

function autoglm_start_session(): void {
    if (session_status() === PHP_SESSION_NONE) {
        session_start();
    }
}

function autoglm_is_logged_in(): bool {
    autoglm_start_session();
    return !empty($_SESSION['autoglm_admin_logged_in']);
}

function autoglm_require_login(): void {
    if (!autoglm_is_logged_in()) {
        header('Location: /PHP/admin/login.php');
        exit;
    }
}

function autoglm_base_url(): string {
    $scheme = 'http';
    $host = $_SERVER['HTTP_HOST'] ?? 'localhost';
    return $scheme . '://' . $host;
}
