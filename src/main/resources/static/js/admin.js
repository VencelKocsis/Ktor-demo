// --- KONSTANSOK ---
const BACKEND_API_URL = "/players";
const CLUBS_API_URL = "/clubs";
const TEAMS_API_URL = "/teams";
const WEBSOCKET_URL = (window.location.protocol === "https:" ? "wss://" : "ws://") + window.location.host + "/ws/players";
const FCM_REGISTRATION_URL = "/register_fcm_token";
const PLACEHOLDER_FCM_TOKEN = 'WEB_TEST_TOKEN_' + crypto.randomUUID();

let ws;
let playersData = [];
let editingPlayer = null;
let teamsDataCache = [];
let clubsDataCache = [];

// --- UI SEGÉDFÜGGVÉNYEK ---

function showStatus(message, isError = false) {
    const statusEl = document.getElementById('status');
    statusEl.textContent = message;
    statusEl.className = `block w-full p-4 mb-6 rounded-lg font-bold text-sm text-center transition-all duration-300 ${isError ? 'bg-red-100 text-red-700 border border-red-200' : 'bg-emerald-100 text-emerald-700 border border-emerald-200'}`;
    statusEl.classList.remove('hidden');

    // 5 mp múlva eltűnik
    setTimeout(() => {
        statusEl.classList.add('hidden');
    }, 5000);
}

function updateWsBadge(isConnected) {
    const wsStatus = document.getElementById('wsStatus');
    const wsDot = document.getElementById('wsDot');
    const wsText = document.getElementById('wsText');

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

// Fülek (Tabs) Váltása
function switchTab(tabId) {
    // Elrejtünk minden tartalmat
    document.querySelectorAll('.tab-content').forEach(el => {
        el.classList.remove('block');
        el.classList.add('hidden');
    });

    // Minden gomb inaktív stílus
    document.querySelectorAll('nav button').forEach(btn => {
        btn.className = "tab-inactive pb-4 px-1 text-base transition-colors duration-200";
    });

    // Aktiváljuk a kiválasztottat
    document.getElementById(tabId).classList.remove('hidden');
    document.getElementById(tabId).classList.add('block');
    document.getElementById(`btn-${tabId}`).className = "tab-active pb-4 px-1 text-base transition-colors duration-200";
}

function showNonBlockingWarning(message, callback) {
    if (window.confirm(message)) {
        callback(true);
    } else {
        callback(false);
    }
}

// --- HÁLÓZATI FUNKCIÓK ---

// --- KLUB FUNKCIÓK ---

async function fetchClubs() {
    try {
        const response = await fetch(CLUBS_API_URL);
        if (!response.ok) throw new Error("Hiba a klubok lekérésénél.");
        clubsDataCache = await response.json();

        // 1. Legördülő lista frissítése
        const clubSelect = document.getElementById('teamClub');
        clubSelect.innerHTML = '<option value="">Válassz klubot...</option>';
        clubsDataCache.forEach(club => {
            clubSelect.innerHTML += `<option value="${club.id}">${club.name}</option>`;
        });

        // 2. Klub kártyák kirajzolása
        const container = document.getElementById('clubsContainer');
        container.innerHTML = '';
        clubsDataCache.forEach(club => {
            container.innerHTML += `
                <div class="bg-white rounded-xl border border-slate-200 p-4 shadow-sm flex justify-between items-center">
                    <div>
                        <h3 class="font-bold text-slate-800">${club.name}</h3>
                        <p class="text-xs text-slate-500">${club.address}</p>
                    </div>
                    <button onclick="openEditClub(${club.id})" class="text-emerald-600 hover:text-emerald-800 text-sm font-bold bg-emerald-50 px-3 py-1.5 rounded-md">Módosít</button>
                </div>
            `;
        });
    } catch (error) {
        console.error(error);
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
        fetchTeams(); // Hátha a csapat kártyákon frissülnie kell a klub nevének
    } catch (error) {
        showStatus(error.message, true);
    }
}

// --- CSAPAT FUNKCIÓK ---

async function fetchTeams() {
    const container = document.getElementById('teamsContainer');
    try {
        const response = await fetch(TEAMS_API_URL);
        if (!response.ok) throw new Error(`Hiba: ${response.status}`);
        teamsDataCache = await response.json();

        container.innerHTML = '';
        teamsDataCache.forEach(team => {
            container.innerHTML += `
                <div class="bg-white rounded-xl border border-slate-200 p-4 hover:shadow-md transition-shadow relative">
                    <button onclick="openEditTeam(${team.teamId})" class="absolute top-4 right-4 text-indigo-600 hover:text-indigo-800 text-sm font-bold bg-indigo-50 px-2 py-1 rounded-md">Módosít</button>
                    <h3 class="text-lg font-extrabold text-slate-800 pr-16">${team.teamName}</h3>
                    <p class="text-xs font-semibold text-indigo-600 mb-2">${team.clubName} • ${team.division || 'N/A'}</p>
                    <div class="text-xs text-slate-500 mt-2 border-t pt-2">Tagok: ${team.members.length} fő</div>
                </div>
            `;
        });
    } catch (error) {
        console.error(error);
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

async function fetchTeams() {
    const container = document.getElementById('teamsContainer');
    container.innerHTML = '<div class="col-span-full text-center py-8 text-indigo-600 font-bold animate-pulse">Csapatok betöltése...</div>';

    try {
        const response = await fetch(TEAMS_API_URL);
        if (!response.ok) throw new Error(`Hiba: ${response.status}`);
        const teamsData = await response.json();

        container.innerHTML = '';
        if (teamsData.length === 0) {
            container.innerHTML = '<div class="col-span-full text-center py-8 text-slate-500">Nincsenek regisztrált csapatok.</div>';
            return;
        }

        teamsData.forEach(team => {
            const card = document.createElement('div');
            card.className = "bg-slate-50 rounded-xl border border-slate-200 p-5 hover:shadow-md transition-shadow";
            card.innerHTML = `
                <div class="flex justify-between items-start mb-4">
                    <div>
                        <h3 class="text-xl font-extrabold text-slate-800">${team.teamName}</h3>
                        <p class="text-sm font-semibold text-indigo-600">${team.clubName} • ${team.division || 'N/A'}</p>
                    </div>
                    <span class="bg-indigo-100 text-indigo-800 text-xs font-bold px-2.5 py-1 rounded-full">${team.points} Pont</span>
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
            `;
            container.appendChild(card);
        });

    } catch (error) {
        console.error(error);
        container.innerHTML = `<div class="col-span-full text-center py-8 text-red-500 font-bold">Hiba a betöltéskor: ${error.message}</div>`;
    }
}

async function fetchClubs() {
    try {
        const response = await fetch(CLUBS_API_URL);
        if (!response.ok) throw new Error("Hiba a klubok lekérésénél.");
        const clubs = await response.json();

        const clubSelect = document.getElementById('teamClub');
        clubSelect.innerHTML = '<option value="">Válassz klubot...</option>';
        clubs.forEach(club => {
            clubSelect.innerHTML += `<option value="${club.id}">${club.name}</option>`;
        });
    } catch (error) {
        console.error("Klubok betöltése sikertelen:", error);
    }
}

async function addTeam(event) {
    event.preventDefault();
    const clubIdStr = document.getElementById('teamClub').value;
    const teamName = document.getElementById('teamName').value.trim();
    const division = document.getElementById('teamDivision').value.trim();

    // 1. Validáció: Van-e kiválasztott klub?
    if (!clubIdStr) {
        showStatus("Kérlek válassz ki egy klubot!", true);
        return;
    }

    // 2. Objektum építése
    const newTeamData = {
        clubId: parseInt(clubIdStr, 10),
        name: teamName
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

        // 3. Ha 400-as hiba van, próbáljuk meg kiolvasni a hiba okát a szervertől!
        if (!response.ok) {
            const errorText = await response.text();
            throw new Error(errorText || `Hiba: ${response.status}`);
        }

        document.getElementById('addTeamForm').reset();
        showStatus('Csapat sikeresen létrehozva!');
        fetchTeams();
    } catch (error) {
        showStatus(error.message, true);
    }
}

async function registerFCMToken() {
    const fcmStatusEl = document.getElementById('fcmStatus');
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

// --- CRUD FUNKCIÓK ---

// --- WEBSOCKET LOGIKA ---

function setupWebSocket() {
    if (ws) ws.close();
    ws = new WebSocket(WEBSOCKET_URL);

    ws.onopen = () => {
        updateWsBadge(true);
        setInterval(() => {
            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify({ type: "ping" }));
            }
        }, 30000);
    };

    ws.onmessage = (event) => {
        try {
            const data = JSON.parse(event.data);
            if (data.type === 'PlayerAdded') {
                if (!playersData.find(p => p.id === data.player.id)) playersData.push(data.player);
            } else if (data.type === 'PlayerDeleted') {
                playersData = playersData.filter(p => p.id !== data.id);
            } else if (data.type === 'PlayerUpdated') {
                playersData = playersData.map(p => p.id === data.player.id ? data.player : p);
            }
            renderPlayers(playersData);
        } catch (e) { console.error("WS Dekódolási hiba:", e); }
    };

    ws.onclose = () => {
        updateWsBadge(false);
        setTimeout(setupWebSocket, 5000);
    };

    ws.onerror = () => ws.close();
}

// --- INIT ---
window.onload = () => {
    fetchClubs();
    setupWebSocket();
    // Első fül aktívvá tétele
    switchTab('playersTab');
}

// Globálissá tétel az inline HTML onclick miatt
window.registerFCMToken = registerFCMToken;
window.addTeam = addTeam;
window.fetchClubs = fetchClubs;
window.addClub = addClub;
window.openEditClub = openEditClub;
window.saveClubEdit = saveClubEdit;

window.openEditTeam = openEditTeam;
window.saveTeamEdit = saveTeamEdit;
window.fetchTeams = fetchTeams;
window.switchTab = switchTab;