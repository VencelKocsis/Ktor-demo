// --- KONSTANSOK ---
const BACKEND_API_URL = "/users/available"; // Ha esetleg van külön játékos végpontod, cseréld le
const CLUBS_API_URL = "/clubs";
const TEAMS_API_URL = "/teams";
const WEBSOCKET_URL = (window.location.protocol === "https:" ? "wss://" : "ws://") + window.location.host + "/ws/players";
const FCM_REGISTRATION_URL = "/register_fcm_token";
const PLACEHOLDER_FCM_TOKEN = 'WEB_TEST_TOKEN_' + crypto.randomUUID();

let ws;
let playersData = [];
let teamsDataCache = [];
let clubsDataCache = [];

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

        // 1. Legördülő lista frissítése az "Új Csapat" űrlaphoz
        const clubSelect = document.getElementById('teamClub');
        if (clubSelect) {
            clubSelect.innerHTML = '<option value="">Válassz klubot...</option>';
            clubsDataCache.forEach(club => {
                clubSelect.innerHTML += `<option value="${club.id}">${club.name}</option>`;
            });
        }

        // 2. Klub kártyák kirajzolása
        const container = document.getElementById('clubsContainer');
        if (container) {
            container.innerHTML = '';
            if (clubsDataCache.length === 0) {
                container.innerHTML = '<div class="col-span-full text-center py-4 text-slate-500">Még nincsenek klubok.</div>';
                return;
            }

            clubsDataCache.forEach(club => {
                container.innerHTML += `
                    <div class="bg-white rounded-xl border border-slate-200 p-4 shadow-sm flex justify-between items-center">
                        <div>
                            <h3 class="font-bold text-slate-800">${club.name}</h3>
                            <p class="text-xs text-slate-500">${club.address}</p>
                        </div>
                        <button onclick="openEditClub(${club.id})" class="text-emerald-600 hover:text-emerald-800 text-sm font-bold bg-emerald-50 px-3 py-1.5 rounded-md transition-colors">Módosít</button>
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
    document.getElementById('editClubModal').classList.remove('hidden');
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
        document.getElementById('editClubModal').classList.add('hidden');
        showStatus('Klub frissítve!');
        fetchClubs();
        fetchTeams();
    } catch (error) {
        showStatus(error.message, true);
    }
}

// --- JÁTÉKOSOK (Csapatkapitány választóhoz kell!) ---
async function fetchPlayers() {
    try {
        // Figyelem: A BACKEND_API_URL végpontnak mindenképpen vissza kell adnia a regisztrált usereket!
        // Ha /players végpontod nincs, használd a /users/available végpontot!
        const response = await fetch("/users/available");
        if (!response.ok) throw new Error(`Hiba: ${response.status}`);
        playersData = await response.json();

        // Csak a legördülő menüt frissítjük
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
    container.innerHTML = '<div class="col-span-full text-center py-8 text-indigo-600 font-bold animate-pulse">Csapatok betöltése...</div>';

    try {
        const response = await fetch(TEAMS_API_URL);
        if (!response.ok) throw new Error(`Hiba: ${response.status}`);
        teamsDataCache = await response.json();

        container.innerHTML = '';
        if (teamsDataCache.length === 0) {
            container.innerHTML = '<div class="col-span-full text-center py-8 text-slate-500">Nincsenek regisztrált csapatok.</div>';
            return;
        }

        teamsDataCache.forEach(team => {
            container.innerHTML += `
                <div class="bg-slate-50 rounded-xl border border-slate-200 p-5 hover:shadow-md transition-shadow relative">
                    <button onclick="openEditTeam(${team.teamId})" class="absolute top-4 right-4 text-indigo-600 hover:text-indigo-800 text-sm font-bold bg-indigo-100 px-3 py-1 rounded-md">Módosít</button>
                    <div class="flex justify-between items-start mb-4 pr-20">
                        <div>
                            <h3 class="text-xl font-extrabold text-slate-800">${team.teamName}</h3>
                            <p class="text-sm font-semibold text-indigo-600">${team.clubName} • ${team.division || 'N/A'}</p>
                        </div>
                    </div>
                    <div class="grid grid-cols-3 gap-2 mb-4 text-center text-sm border-t border-b border-slate-200 py-3">
                        <div><span class="block text-slate-500 text-xs uppercase font-bold">Győzelem</span><span class="font-bold text-emerald-600">${team.wins}</span></div>
                        <div class="border-l border-r border-slate-200"><span class="block text-slate-500 text-xs uppercase font-bold">Döntetlen</span><span class="font-bold text-amber-500">${team.draws}</span></div>
                        <div><span class="block text-slate-500 text-xs uppercase font-bold">Vereség</span><span class="font-bold text-rose-600">${team.losses}</span></div>
                    </div>
                    <div>
                        <h4 class="text-xs font-bold text-slate-500 uppercase mb-2">Csapattagok (${team.members.length})</h4>
                        <div class="flex flex-wrap gap-1.5">
                            ${team.members.map(m => `<span class="inline-block bg-white border border-slate-200 text-slate-700 text-xs px-2 py-1 rounded-md shadow-sm ${m.isCaptain ? 'ring-1 ring-amber-400 font-bold' : ''}">${m.isCaptain ? '⭐ ' : ''}${m.name}</span>`).join('')}
                        </div>
                    </div>
                </div>
            `;
        });
    } catch (error) {
        console.error(error);
        container.innerHTML = `<div class="col-span-full text-center py-8 text-red-500 font-bold">Hiba a betöltéskor: ${error.message}</div>`;
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
        fetchPlayers(); // Újratöltjük a kapitányokat, mert egy játékos bekerült egy csapatba
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
    document.getElementById('editTeamModal').classList.remove('hidden');
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
        document.getElementById('editTeamModal').classList.add('hidden');
        showStatus('Csapat frissítve!');
        fetchTeams();
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
    switchTab('teamsTab'); // Induláskor a Csapatok fület mutatjuk, ha a Játékosok már nincsenek
}

// Hogy a HTML-ből hívhatók legyenek a függvények:
window.registerFCMToken = registerFCMToken;
window.fetchClubs = fetchClubs;
window.addClub = addClub;
window.openEditClub = openEditClub;
window.saveClubEdit = saveClubEdit;
window.fetchTeams = fetchTeams;
window.addTeam = addTeam;
window.openEditTeam = openEditTeam;
window.saveTeamEdit = saveTeamEdit;
window.switchTab = switchTab;