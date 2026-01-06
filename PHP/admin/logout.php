<?php

declare(strict_types=1);

require_once __DIR__ . '/../common.php';

autoglm_start_session();
$_SESSION['autoglm_admin_logged_in'] = 0;
session_destroy();

header('Location: /PHP/admin/login.php');
exit;
