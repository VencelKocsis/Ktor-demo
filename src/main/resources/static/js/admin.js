// --- KONSTANSOK ---
const BACKEND_API_URL = "/players";
const TEAMS_API_URL = "/teams";
const WEBSOCKET_URL = (window.location.protocol === "https:" ? "wss://" : "ws://") + window.location.host + "/ws/players";
const FCM_REGISTRATION_URL = "/register_fcm_token";
const PLACEHOLDER_FCM_TOKEN = 'WEB_TEST_TOKEN_' + crypto.randomUUID();

let ws;
let playersData = [];
let editingPlayer = null;

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

async function fetchPlayers() {
    try {
        const response = await fetch(BACKEND_API_URL);
        if (!response.ok) throw new Error(`Hiba: ${response.status}`);
        playersData = await response.json();
        renderPlayers(playersData);
        showStatus(`Sikeresen betöltve ${playersData.length} játékos.`);
    } catch (error) {
        console.error(error);
        showStatus(`Hiba a játékosok betöltésekor: ${error.message}`, true);
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

async function addPlayer(event) {
    event.preventDefault();
    const nameInput = document.getElementById('playerName').value.trim();
    const ageInput = document.getElementById('playerAge').value.trim();
    const emailInput = document.getElementById('playerEmail').value.trim();

    const newPlayerDTO = {
        name: nameInput,
        age: ageInput === "" ? null : parseInt(ageInput, 10),
        email: emailInput
    };

    try {
        const response = await fetch(BACKEND_API_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(newPlayerDTO)
        });
        if (!response.ok) throw new Error('Hiba a mentésnél');

        document.getElementById('addPlayerForm').reset();
        showStatus('Játékos sikeresen hozzáadva!');
    } catch (error) {
        showStatus(error.message, true);
    }
}

function deletePlayer(id) {
    showNonBlockingWarning(`Biztosan törölni szeretné ezt a játékost?`, async (confirmed) => {
        if (!confirmed) return;
        try {
            const response = await fetch(`${BACKEND_API_URL}/${id}`, { method: 'DELETE' });
            if (!response.ok) throw new Error('Hiba a törlésnél');
            showStatus('Játékos sikeresen törölve!');
        } catch (error) {
            showStatus(error.message, true);
        }
    });
}

function handleEdit(player) {
    editingPlayer = player;
    document.getElementById('editPlayerId').value = player.id;
    document.getElementById('editPlayerName').value = player.name;
    document.getElementById('editPlayerAge').value = player.age || '';
    document.getElementById('editPlayerEmail').value = player.email || '';

    const modal = document.getElementById('editModal');
    modal.classList.remove('hidden');
    // Kis delay az animációhoz
    setTimeout(() => {
        modal.classList.remove('opacity-0');
        document.getElementById('editModalContent').classList.remove('scale-95');
    }, 10);
}

function closeEditModal() {
    editingPlayer = null;
    const modal = document.getElementById('editModal');
    modal.classList.add('opacity-0');
    document.getElementById('editModalContent').classList.add('scale-95');

    setTimeout(() => {
        modal.classList.add('hidden');
    }, 300);
}

async function saveEdit(event) {
    event.preventDefault();
    if (!editingPlayer) return;

    const updatedPlayerDTO = {
        name: document.getElementById('editPlayerName').value.trim(),
        age: document.getElementById('editPlayerAge').value.trim() ? parseInt(document.getElementById('editPlayerAge').value, 10) : null,
        email: document.getElementById('editPlayerEmail').value.trim()
    };

    try {
        const response = await fetch(`${BACKEND_API_URL}/${editingPlayer.id}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(updatedPlayerDTO)
        });
        if (!response.ok) throw new Error('Hiba a frissítésnél');

        closeEditModal();
        showStatus('Adatok sikeresen frissítve!');
    } catch (error) {
        showStatus(error.message, true);
    }
}

function renderPlayers(players) {
    const tbody = document.getElementById('playerTableBody');
    if (!tbody) return;

    tbody.innerHTML = '';
    if (players.length === 0) {
        tbody.innerHTML = '<tr><td colspan="4" class="py-6 text-center text-slate-500 italic">Nincsenek játékosok az adatbázisban.</td></tr>';
        return;
    }

    const sortedPlayers = [...players].sort((a, b) => a.id - b.id);

    sortedPlayers.forEach(player => {
        const tr = document.createElement('tr');
        tr.className = 'hover:bg-slate-50 transition-colors border-b border-slate-100 last:border-0';
        tr.innerHTML = `
            <td class="py-3 px-6 whitespace-nowrap text-sm font-semibold text-slate-600">#${player.id}</td>
            <td class="py-3 px-6">
                <div class="font-bold text-slate-800">${player.name || 'N/A'}</div>
                <div class="text-xs text-slate-500">${player.email || 'Nincs email'}</div>
            </td>
            <td class="py-3 px-6 text-sm text-slate-600">${player.age || '-'}</td>
            <td class="py-3 px-6 text-right whitespace-nowrap">
                <button onclick='handleEdit(${JSON.stringify(player).replace(/'/g, "&#39;")})' class="text-amber-500 hover:text-amber-700 font-bold px-2 py-1 rounded bg-amber-50 hover:bg-amber-100 mr-2 transition-colors text-sm">Szerkesztés</button>
                <button onclick="deletePlayer(${player.id})" class="text-rose-500 hover:text-rose-700 font-bold px-2 py-1 rounded bg-rose-50 hover:bg-rose-100 transition-colors text-sm">Törlés</button>
            </td>
        `;
        tbody.appendChild(tr);
    });
}

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
    fetchPlayers();
    setupWebSocket();
    // Első fül aktívvá tétele
    switchTab('playersTab');
}

// Globálissá tétel az inline HTML onclick miatt
window.handleEdit = handleEdit;
window.deletePlayer = deletePlayer;
window.registerFCMToken = registerFCMToken;
window.addPlayer = addPlayer;
window.closeEditModal = closeEditModal;
window.saveEdit = saveEdit;
window.fetchPlayers = fetchPlayers;
window.fetchTeams = fetchTeams;
window.switchTab = switchTab;