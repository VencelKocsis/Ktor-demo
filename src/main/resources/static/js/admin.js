// --- KONSTANSOK ---
const BACKEND_API_URL = "/users"; // Mivel nincsenek külön playerek, a users végpontot hívjuk
const CLUBS_API_URL = "/clubs";
const TEAMS_API_URL = "/teams";
const SEASONS_API_URL = "/seasons";
const MATCHES_API_URL = "/matches";
const WEBSOCKET_URL = (window.location.protocol === "https:" ? "wss://" : "ws://") + window.location.host + "/ws/players";
const FCM_REGISTRATION_URL = "/register_fcm_token";
const PLACEHOLDER_FCM_TOKEN = 'WEB_TEST_TOKEN_' + crypto.randomUUID();

let ws;
let playersData = [];
let teamsDataCache = [];
let clubsDataCache = [];

// --- SÖTÉT MÓD LOGIKA ---

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

if (localStorage.theme === 'dark' || (!('theme' in localStorage) && window.matchMedia('(prefers-color-scheme: dark)').matches)) {
    document.documentElement.classList.add('dark');
    document.addEventListener('DOMContentLoaded', () => updateThemeIcon(true));
} else {
    document.documentElement.classList.remove('dark');
    document.addEventListener('DOMContentLoaded', () => updateThemeIcon(false));
}

// --- UI SEGÉDFÜGGVÉNYEK ---

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

function updateWsBadge(isConnected) {
    const wsStatus = document.getElementById('wsStatus');
    const wsDot = document.getElementById('wsDot');
    const wsText = document.getElementById('wsText');
    if (!wsStatus) return;

    if (isConnected) {
        wsStatus.className = "inline-flex items-center gap-1.5 rounded-full bg-emerald-50 border border-emerald-200 px-3 py-1 text-sm font-semibold text-emerald-700 shadow-sm";
        wsDot.className = "w-2 h-2 rounded-full bg-emerald-500 animate-pulse";
        wsText.textContent = "WS Aktív";
    } else {
        wsStatus.className = "inline-flex items-center gap-1.5 rounded-full bg-red-50 border border-red-200 px-3 py-1 text-sm font-semibold text-red-700 shadow-sm";
        wsDot.className = "w-2 h-2 rounded-full bg-red-500";
        wsText.textContent = "WS Bontva";
    }
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

// --- KLUB FUNKCIÓK ---

async function fetchClubs() {
    try {
        const response = await fetch(CLUBS_API_URL);
        if (!response.ok) throw new Error("Hiba a klubok lekérésénél.");

        clubsDataCache = await response.json();

        const clubSelect = document.getElementById('teamClub');
        if (clubSelect) {
            clubSelect.innerHTML = '<option value="">Válassz klubot...</option>';
            clubsDataCache.forEach(club => {
                clubSelect.innerHTML += `<option value="${club.id}">${club.name}</option>`;
            });
        }

        const container = document.getElementById('clubsContainer');
        if (container) {
            container.innerHTML = '';

            if (clubsDataCache.length === 0) {
                container.innerHTML = '<div class="col-span-full text-center py-4 text-slate-500 dark:text-slate-400">Még nincsenek klubok.</div>';
                return;
            }

            clubsDataCache.forEach(club => {
                container.innerHTML += `
                    <div class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-4 shadow-sm flex justify-between items-center transition-colors">
                        <div>
                            <h3 class="font-bold text-slate-800 dark:text-white">${club.name}</h3>
                            <p class="text-xs text-slate-500 dark:text-slate-400">${club.address}</p>
                        </div>
                        <button onclick="openEditClub(${club.id})" class="text-emerald-600 dark:text-emerald-400 hover:text-emerald-800 dark:hover:text-emerald-300 text-sm font-bold bg-emerald-50 dark:bg-emerald-900/30 px-3 py-1.5 rounded-md transition-colors">Módosít</button>
                    </div>
                `;
            });
        }

    } catch (error) {
        console.error("Klubok betöltése sikertelen:", error);
    }
}

async function addClub(event) {
    event.preventDefault();
    const newClubData = {
        name: document.getElementById('clubName').value.trim(),
        address: document.getElementById('clubAddress').value.trim()
    };
    try {
        const response = await fetch(CLUBS_API_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(newClubData)
        });
        if (!response.ok) throw new Error('Hiba a mentésnél');
        document.getElementById('addClubForm').reset();
        showStatus('Klub sikeresen létrehozva!');
        fetchClubs();
    } catch (error) {
        showStatus(error.message, true);
    }
}

function openEditClub(id) {
    const club = clubsDataCache.find(c => c.id === id);
    if (!club) return;
    document.getElementById('editClubId').value = club.id;
    document.getElementById('editClubName').value = club.name;
    document.getElementById('editClubAddress').value = club.address;

    const modal = document.getElementById('editClubModal');
    modal.classList.remove('hidden');
    modal.classList.add('flex');
}

function closeEditClubModal() {
    const modal = document.getElementById('editClubModal');
    modal.classList.add('hidden');
    modal.classList.remove('flex');
}

async function saveClubEdit(event) {
    event.preventDefault();
    const id = document.getElementById('editClubId').value;
    const updateData = {
        name: document.getElementById('editClubName').value.trim(),
        address: document.getElementById('editClubAddress').value.trim()
    };
    try {
        const response = await fetch(`${CLUBS_API_URL}/${id}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(updateData)
        });
        if (!response.ok) throw new Error('Hiba a frissítésnél');
        closeEditClubModal();
        showStatus('Klub frissítve!');
        fetchClubs();
        fetchTeams();
    } catch (error) {
        showStatus(error.message, true);
    }
}

// --- JÁTÉKOSOK (USERS) ---
async function fetchPlayers() {
    try {
        const response = await fetch("/users");
        if (!response.ok) throw new Error(`Hiba: ${response.status}`);
        playersData = await response.json();

        const captainSelect = document.getElementById('teamCaptain');
        if (captainSelect) {
            captainSelect.innerHTML = '<option value="">Kapitány kiválasztása...</option>';
            playersData.forEach(player => {
                captainSelect.innerHTML += `<option value="${player.userId}">${player.name || 'Névtelen'}</option>`;
            });
        }
    } catch (error) {
        console.error("Nem sikerült lekérni a játékosokat a kapitány listához:", error);
    }
}

// --- CSAPAT FUNKCIÓK ---

async function fetchTeams() {
    const container = document.getElementById('teamsContainer');
    if (!container) return;

    container.innerHTML = '<div class="col-span-full text-center py-8 text-indigo-600 dark:text-indigo-400 font-bold animate-pulse">Csapatok betöltése...</div>';

    try {
        const response = await fetch(TEAMS_API_URL);
        if (!response.ok) throw new Error(`Hiba: ${response.status}`);
        teamsDataCache = await response.json();

        container.innerHTML = '';
        if (teamsDataCache.length === 0) {
            container.innerHTML = '<div class="col-span-full text-center py-8 text-slate-500 dark:text-slate-400">Nincsenek regisztrált csapatok.</div>';
            return;
        }

        teamsDataCache.forEach(team => {
            container.innerHTML += `
                <div class="bg-slate-50 dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-5 hover:shadow-md transition-all relative">
                    <button onclick="openEditTeam(${team.teamId})" class="absolute top-4 right-4 text-indigo-600 dark:text-indigo-400 hover:text-indigo-800 dark:hover:text-indigo-300 text-sm font-bold bg-indigo-100 dark:bg-indigo-900/30 px-3 py-1 rounded-md transition-colors">Módosít</button>
                    <div class="flex justify-between items-start mb-4 pr-20">
                        <div>
                            <h3 class="text-xl font-extrabold text-slate-800 dark:text-white">${team.teamName}</h3>
                            <p class="text-sm font-semibold text-indigo-600 dark:text-indigo-400">${team.clubName} • ${team.division || 'N/A'}</p>
                        </div>
                    </div>
                    <div class="grid grid-cols-3 gap-2 mb-4 text-center text-sm border-t border-b border-slate-200 dark:border-slate-700 py-3">
                        <div><span class="block text-slate-500 dark:text-slate-400 text-xs uppercase font-bold">Győzelem</span><span class="font-bold text-emerald-600 dark:text-emerald-400">${team.wins}</span></div>
                        <div class="border-l border-r border-slate-200 dark:border-slate-700"><span class="block text-slate-500 dark:text-slate-400 text-xs uppercase font-bold">Döntetlen</span><span class="font-bold text-amber-500 dark:text-amber-400">${team.draws}</span></div>
                        <div><span class="block text-slate-500 dark:text-slate-400 text-xs uppercase font-bold">Vereség</span><span class="font-bold text-rose-600 dark:text-rose-400">${team.losses}</span></div>
                    </div>
                    <div>
                        <h4 class="text-xs font-bold text-slate-500 dark:text-slate-400 uppercase mb-2">Csapattagok (${team.members.length})</h4>
                        <div class="flex flex-wrap gap-1.5">
                            ${team.members.map(m => `<span class="inline-block bg-white dark:bg-slate-700 border border-slate-200 dark:border-slate-600 text-slate-700 dark:text-slate-200 text-xs px-2 py-1 rounded-md shadow-sm ${m.isCaptain ? 'ring-1 ring-amber-400 dark:ring-amber-500 font-bold' : ''}">${m.isCaptain ? '⭐ ' : ''}${m.name}</span>`).join('')}
                        </div>
                    </div>
                </div>
            `;
        });

        if (typeof updateMatchTeamDropdowns === "function") {
            updateMatchTeamDropdowns();
        }

    } catch (error) {
        console.error(error);
        container.innerHTML = `<div class="col-span-full text-center py-8 text-rose-500 dark:text-rose-400 font-bold">Hiba a betöltéskor: ${error.message}</div>`;
    }
}

async function addTeam(event) {
    event.preventDefault();
    const clubIdStr = document.getElementById('teamClub').value;
    const teamName = document.getElementById('teamName').value.trim();
    const division = document.getElementById('teamDivision').value.trim();
    const captainIdStr = document.getElementById('teamCaptain').value;

    if (!clubIdStr || !captainIdStr) {
        showStatus("Kérlek válassz ki egy klubot és egy kapitányt is!", true);
        return;
    }

    const newTeamData = {
        clubId: parseInt(clubIdStr, 10),
        name: teamName,
        captainUserId: parseInt(captainIdStr, 10)
    };

    if (division.length > 0) {
        newTeamData.division = division;
    }

    try {
        const response = await fetch(TEAMS_API_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(newTeamData)
        });

        if (!response.ok) {
            const errorText = await response.text();
            throw new Error(errorText || `Hiba: ${response.status}`);
        }

        document.getElementById('addTeamForm').reset();
        showStatus('Csapat sikeresen létrehozva a kapitánnyal együtt!');
        fetchTeams();
        fetchPlayers();
    } catch (error) {
        showStatus(error.message, true);
    }
}

function openEditTeam(id) {
    const team = teamsDataCache.find(t => t.teamId === id);
    if (!team) return;
    document.getElementById('editTeamId').value = team.teamId;
    document.getElementById('editTeamName').value = team.teamName;
    document.getElementById('editTeamDivision').value = team.division || '';

    const modal = document.getElementById('editTeamModal');
    modal.classList.remove('hidden');
    modal.classList.add('flex');
}

function closeEditTeamModal() {
    const modal = document.getElementById('editTeamModal');
    modal.classList.add('hidden');
    modal.classList.remove('flex');
}

async function saveTeamEdit(event) {
    event.preventDefault();
    const id = document.getElementById('editTeamId').value;
    const divisionVal = document.getElementById('editTeamDivision').value.trim();
    const updateData = {
        name: document.getElementById('editTeamName').value.trim(),
        division: divisionVal.length > 0 ? divisionVal : null
    };
    try {
        const response = await fetch(`${TEAMS_API_URL}/${id}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(updateData)
        });
        if (!response.ok) throw new Error('Hiba a frissítésnél');
        closeEditTeamModal();
        showStatus('Csapat frissítve!');
        fetchTeams();
    } catch (error) {
        showStatus(error.message, true);
    }
}

// --- MÉRKŐZÉS FUNKCIÓK ---

async function fetchSeasons() {
    try {
        const response = await fetch(SEASONS_API_URL);
        if (!response.ok) throw new Error("Hiba a szezonok betöltésekor");
        const seasons = await response.json();

        const seasonSelect = document.getElementById('matchSeason');
        if (seasonSelect) {
            seasonSelect.innerHTML = '<option value="">Szezon kiválasztása...</option>';
            seasons.forEach(s => {
                const selected = s.isActive ? 'selected' : '';
                seasonSelect.innerHTML += `<option value="${s.id}" ${selected}>${s.name}</option>`;
            });
        }
    } catch (error) {
        console.error(error);
    }
}

function updateMatchTeamDropdowns() {
    const homeSelect = document.getElementById('matchHomeTeam');
    const guestSelect = document.getElementById('matchGuestTeam');

    if (homeSelect && guestSelect && teamsDataCache.length > 0) {
        homeSelect.innerHTML = '<option value="">Hazai csapat...</option>';
        guestSelect.innerHTML = '<option value="">Vendég csapat...</option>';

        teamsDataCache.forEach(team => {
            homeSelect.innerHTML += `<option value="${team.teamId}">${team.teamName}</option>`;
            guestSelect.innerHTML += `<option value="${team.teamId}">${team.teamName}</option>`;
        });
    }
}

async function fetchMatches() {
    const container = document.getElementById('matchesContainer');
    if (!container) return;
    container.innerHTML = '<div class="text-center py-4 text-slate-500">Mérkőzések betöltése...</div>';

    try {
        const response = await fetch(MATCHES_API_URL);
        if (!response.ok) throw new Error("Hiba a meccsek lekérésénél");
        const matches = await response.json();

        container.innerHTML = '';
        if (matches.length === 0) {
            container.innerHTML = '<div class="text-center py-4 text-slate-500">Nincsenek kiírt mérkőzések.</div>';
            return;
        }

        matches.sort((a, b) => b.id - a.id).forEach(match => {
            let statusBadge = '';
            if (match.status === 'scheduled') statusBadge = '<span class="bg-blue-100 text-blue-800 text-xs font-bold px-2 py-1 rounded">Kiírva</span>';
            else if (match.status === 'in_progress') statusBadge = '<span class="bg-amber-100 text-amber-800 text-xs font-bold px-2 py-1 rounded">Élő</span>';
            else statusBadge = '<span class="bg-emerald-100 text-emerald-800 text-xs font-bold px-2 py-1 rounded">Befejezve</span>';

            const dateObj = new Date(match.date);
            const formattedDate = dateObj.toLocaleString('hu-HU', { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute:'2-digit' });

            container.innerHTML += `
                <div class="bg-slate-50 dark:bg-slate-700 rounded-xl border border-slate-200 dark:border-slate-600 p-4 flex justify-between items-center">
                    <div>
                        <div class="flex items-center gap-2 mb-1">
                            <span class="text-xs font-bold text-slate-500 dark:text-slate-400 uppercase">${match.roundNumber}. Kör</span>
                            ${statusBadge}
                        </div>
                        <h3 class="text-lg font-bold text-slate-800 dark:text-white">
                            ${match.homeTeamName} <span class="text-slate-400">vs</span> ${match.guestTeamName}
                        </h3>
                        <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">
                            📅 ${formattedDate} | 📍 ${match.location}
                        </p>
                    </div>
                    <div class="text-right">
                        <div class="text-2xl font-black ${match.status === 'finished' ? 'text-indigo-600 dark:text-indigo-400' : 'text-slate-700 dark:text-slate-300'}">
                            ${match.status === 'scheduled' ? '- : -' : `${match.homeScore} : ${match.guestScore}`}
                        </div>
                    </div>
                </div>
            `;
        });
    } catch (error) {
        console.error(error);
        container.innerHTML = `<div class="text-center py-4 text-rose-500">Hiba: ${error.message}</div>`;
    }
}

async function addMatch(event) {
    event.preventDefault();
    const seasonId = document.getElementById('matchSeason').value;
    const roundNumber = document.getElementById('matchRound').value;
    const homeTeamId = document.getElementById('matchHomeTeam').value;
    const guestTeamId = document.getElementById('matchGuestTeam').value;
    const matchDate = document.getElementById('matchDate').value;
    const location = document.getElementById('matchLocation').value.trim();

    if (homeTeamId === guestTeamId) {
        showStatus("A hazai és vendég csapat nem lehet ugyanaz!", true);
        return;
    }

    const newMatchData = {
        seasonId: parseInt(seasonId, 10),
        roundNumber: parseInt(roundNumber, 10),
        homeTeamId: parseInt(homeTeamId, 10),
        guestTeamId: parseInt(guestTeamId, 10),
        matchDate: matchDate,
        location: location
    };

    try {
        const response = await fetch(MATCHES_API_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(newMatchData)
        });

        if (!response.ok) {
            const errorText = await response.text();
            throw new Error(errorText || `Hiba: ${response.status}`);
        }

        document.getElementById('addMatchForm').reset();
        showStatus('Mérkőzés sikeresen kiírva!');
        fetchMatches();
    } catch (error) {
        showStatus(error.message, true);
    }
}

// --- FCM TESZT ---

async function registerFCMToken() {
    const fcmStatusEl = document.getElementById('fcmStatus');
    if (!fcmStatusEl) return;
    fcmStatusEl.textContent = 'Küldés...';
    fcmStatusEl.className = 'text-sm font-semibold text-amber-500';

    try {
        const response = await fetch(FCM_REGISTRATION_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ fcm_token: PLACEHOLDER_FCM_TOKEN })
        });
        if (!response.ok) throw new Error('Regisztrációs hiba');
        fcmStatusEl.textContent = 'Sikeres Teszt (Mock Token)';
        fcmStatusEl.className = 'text-sm font-semibold text-emerald-600';
    } catch (error) {
        fcmStatusEl.textContent = 'Hiba történt';
        fcmStatusEl.className = 'text-sm font-semibold text-rose-600';
    }
}

// --- INIT ÉS BINDING ---
window.onload = () => {
    fetchClubs();
    fetchTeams();
    fetchPlayers();
    fetchSeasons();
    fetchMatches();
    switchTab('teamsTab');
}

window.toggleDarkMode = toggleDarkMode;
window.registerFCMToken = registerFCMToken;

window.fetchClubs = fetchClubs;
window.addClub = addClub;
window.openEditClub = openEditClub;
window.saveClubEdit = saveClubEdit;
window.closeEditClubModal = closeEditClubModal;

window.fetchTeams = fetchTeams;
window.addTeam = addTeam;
window.openEditTeam = openEditTeam;
window.saveTeamEdit = saveTeamEdit;
window.closeEditTeamModal = closeEditTeamModal;

window.fetchMatches = fetchMatches;
window.addMatch = addMatch;

window.switchTab = switchTab;