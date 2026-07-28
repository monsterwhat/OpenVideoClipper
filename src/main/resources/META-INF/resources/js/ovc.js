(function() {
  const TOAST_DURATION = 4000;
  const toastContainer = document.getElementById('toast-container');
  if (!toastContainer) return;

  window.showToast = function(message, type, duration) {
    type = type || 'info';
    duration = duration || TOAST_DURATION;
    const colors = {
      success: 'has-background-success has-text-white',
      info: 'has-background-info has-text-white',
      warning: 'has-background-warning has-text-dark',
      danger: 'has-background-danger has-text-white'
    };
    const colorClass = colors[type] || colors.info;
    const toast = document.createElement('div');
    toast.className = 'toast-notification notification is-light ' + colorClass;
    toast.style.cssText = 'margin-bottom: 8px; padding: 12px 16px; border-radius: 6px; box-shadow: 0 4px 12px rgba(0,0,0,0.15); animation: toastIn 0.3s ease; cursor: pointer;';
    toast.innerHTML = '<button class="delete" style="background: rgba(255,255,255,0.3);"></button>' + escapeHtml(message);
    toast.querySelector('.delete').addEventListener('click', function(e) {
      e.stopPropagation();
      dismissToast(toast);
    });
    toast.addEventListener('click', function() { dismissToast(toast); });
    toastContainer.appendChild(toast);
    setTimeout(function() { dismissToast(toast); }, duration);
  };

  function dismissToast(el) {
    if (!el || el.classList.contains('toast-dismissing')) return;
    el.classList.add('toast-dismissing');
    el.style.animation = 'toastOut 0.3s ease forwards';
    setTimeout(function() { if (el.parentNode) el.parentNode.removeChild(el); }, 300);
  }

  function escapeHtml(text) {
    var d = document.createElement('div');
    d.textContent = text;
    return d.innerHTML;
  }

  const style = document.createElement('style');
  style.textContent =
    '@keyframes toastIn { from { opacity: 0; transform: translateX(100%); } to { opacity: 1; transform: translateX(0); } } ' +
    '@keyframes toastOut { from { opacity: 1; transform: translateX(0); } to { opacity: 0; transform: translateX(100%); } } ' +
    '#toast-container { position: fixed; top: 16px; right: 16px; z-index: 9999; max-width: 400px; width: 100%; pointer-events: none; } ' +
    '#toast-container > * { pointer-events: auto; }';
  document.head.appendChild(style);

  document.body.addEventListener('htmx:responseError', function(e) {
    var status = e.detail.xhr.status;
    if (status === 0) {
      showToast('Network error - please check your connection', 'danger', 6000);
    } else if (status >= 500) {
      showToast('Server error (' + status + '). Please try again.', 'danger', 6000);
    } else if (status === 404) {
      showToast('Resource not found (404)', 'warning');
    }
  });

  document.body.addEventListener('htmx:beforeSwap', function(e) {
    if (e.detail.xhr.status >= 400) {
      e.detail.shouldSwap = true;
      e.detail.isError = false;
    }
  });

  // Restore notification: check for unsaved edits on load
  if (window.OVCState) {
    var unsyncedCount = OVCState.getUnsyncedCount();
    if (unsyncedCount > 0) {
      showToast('You have ' + unsyncedCount + ' unsaved transcription edit' + (unsyncedCount === 1 ? '' : 's') + '.', 'warning', 8000);
    }
  }
})();
