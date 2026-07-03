(function () {
    const bridge = window.AndroidAppUpdate;
    if (!bridge) {
        return;
    }

    function showToast(message, type) {
        if (typeof showToastMessage === 'function') {
            showToastMessage(message, type || 'info');
        } else if (typeof window.showToast === 'function' && window.showToast !== showToast) {
            window.showToast(message, type || 'info');
        } else {
            alert(message);
        }
    }

    window.checkAndroidAppUpdate = function () {
        if (!bridge.checkForUpdate) {
            showToast('当前 App 不支持检查更新', 'error');
            return;
        }
        showToast('正在检查更新…', 'info');
        bridge.checkForUpdate();
    };

    function initAndroidUpdateUi() {
        const section = document.getElementById('androidAppUpdateSection');
        const btn = document.getElementById('androidCheckUpdateBtn');
        const versionEl = document.getElementById('androidAppVersionText');

        if (section) {
            section.classList.remove('hidden');
        }

        if (versionEl && bridge.getAppVersion) {
            try {
                versionEl.textContent = '当前版本：' + bridge.getAppVersion();
            } catch (e) {
                versionEl.textContent = '';
            }
        }

        if (btn) {
            const enabled = !bridge.isUpdateEnabled || bridge.isUpdateEnabled();
            if (enabled) {
                btn.classList.remove('hidden');
                btn.addEventListener('click', function () {
                    window.checkAndroidAppUpdate();
                });
            } else {
                btn.classList.add('hidden');
                if (versionEl) {
                    versionEl.textContent += (versionEl.textContent ? ' · ' : '') + '未配置在线更新地址';
                }
            }
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initAndroidUpdateUi);
    } else {
        initAndroidUpdateUi();
    }
})();