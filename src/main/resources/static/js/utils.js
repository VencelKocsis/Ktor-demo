// ==========================================
// KONSTANSOK & GLOBÁLIS ÁLLAPOT
// ==========================================
const BACKEND_API_URL = "/users";
const CLUBS_API_URL = "/clubs";
const TEAMS_API_URL = "/teams";
const SEASONS_API_URL = "/seasons";
const MATCHES_API_URL = "/matches";
const FCM_REGISTRATION_URL = "/register_fcm_token";
const PLACEHOLDER_FCM_TOKEN = 'WEB_TEST_TOKEN_' + crypto.randomUUID();

// Globális cache-ek, amiket a többi fájl is látni fog
let playersData = [];
let teamsDataCache = [];
let clubsDataCache = [];

// ==========================================
// UI SEGÉDFÜGGVÉNYEK
// ==========================================
function showStatus(message, isError = false) {
    const statusEl = document.getElementById('status');
    if (!statusEl) return;
    statusEl.textContent = message;
    statusEl.className = `block w-full p-4 mb-6 rounded-lg font-bold text-sm text-center transition-all duration-300 ${isError ? 'bg-red-100 text-red-700 border border-red-200' : 'bg-emerald-100 text-emerald-700 border border-emerald-200'}`;
    statusEl.classList.remove('hidden');

    setTimeout(() => {
        statusEl.classList.add('hidden');
    }, 5000);
}

function switchTab(tabId) {
    document.querySelectorAll('.tab-content').forEach(el => {
        el.classList.remove('block');
        el.classList.add('hidden');
    });
    document.querySelectorAll('nav button').forEach(btn => {
        btn.className = "tab-inactive pb-4 px-1 text-base transition-colors duration-200";
    });

    const targetTab = document.getElementById(tabId);
    const targetBtn = document.getElementById(`btn-${tabId}`);
    if (targetTab) {
        targetTab.classList.remove('hidden');
        targetTab.classList.add('block');
    }
    if (targetBtn) {
        targetBtn.className = "tab-active pb-4 px-1 text-base transition-colors duration-200";
    }
}

// ==========================================
// SÖTÉT MÓD LOGIKA
// ==========================================
function updateThemeIcon(isDark) {
    const icon = document.getElementById('themeIcon');
    if (!icon) return;
    if (isDark) {
        icon.innerHTML = `<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z"></path>`;
    } else {
        icon.innerHTML = `<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z"></path>`;
    }
}

function toggleDarkMode() {
    document.documentElement.classList.toggle('dark');
    const isDark = document.documentElement.classList.contains('dark');
    localStorage.setItem('theme', isDark ? 'dark' : 'light');
    updateThemeIcon(isDark);
}

// Sötét mód init azonnal
if (localStorage.theme === 'dark' || (!('theme' in localStorage) && window.matchMedia('(prefers-color-scheme: dark)').matches)) {
    document.documentElement.classList.add('dark');
    document.addEventListener('DOMContentLoaded', () => updateThemeIcon(true));
} else {
    document.documentElement.classList.remove('dark');
    document.addEventListener('DOMContentLoaded', () => updateThemeIcon(false));
}