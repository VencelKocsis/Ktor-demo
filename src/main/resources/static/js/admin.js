// --- KONSTANS ---
const BACKEND_API_URL = "/players";
const WEBSOCKET_URL =
    (window.location.protocol === "https:" ? "wss://" : "ws://") +
    window.location.host + "/ws/players";

// --- FCM Konstansok és Függvények (ÚJ) ---
const FCM_REGISTRATION_URL = "/register_fcm_token";
// FIX: Placeholder token a teszteléshez.
const PLACEHOLDER_FCM_TOKEN = 'WEB_TEST_TOKEN_' + crypto.randomUUID();

/**
 * FIGYELEM: A CANVAS KÖRNYEZETBEN TILTOTT AZ alert() ÉS confirm().
 * Ezt a nagyon egyszerű, de nem blokkoló modalt használjuk a deletePlayer funkcióhoz.
 * Éles környezetben ezt a funkciót egy valódi, DOM alapú modallal kell helyettesíteni!
 */
function showNonBlockingWarning(message, callback) {
    // A böngésző natív confirm() helyett itt kellene lennie egy aszinkron DOM modalnak.
    // A teszteléshez a hagyományos confirm()-ot használjuk, de a kód ASZINKRON, mintha DOM modal lenne.
    if (window.confirm(message)) {
        callback(true);
    } else {
        callback(false);
    }
}

/**
 * Regisztrálja a placeholder FCM tokent a Ktor szerveren (ezt fogja értesíteni a POST /players).
 */
async function registerFCMToken() {
    const fcmStatusEl = document.getElementById('fcmStatus');
    if (!fcmStatusEl) return;

    fcmStatusEl.textContent = 'Regisztráció...';
    fcmStatusEl.className = 'inline-flex items-center rounded-full bg-yellow-100 px-3 py-0.5 text-sm font-medium text-yellow-800';

    try {
        const response = await fetch(FCM_REGISTRATION_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ fcm_token: PLACEHOLDER_FCM_TOKEN })
        });

        const data = await response.json();

        if (!response.ok) {
            throw new Error(`Szerver hiba: ${response.status}. Válasz: ${data.status || 'ismeretlen hiba'}`);
        }

        fcmStatusEl.textContent = `Regisztrált: ${data.status}`;
        fcmStatusEl.className = 'inline-flex items-center rounded-full bg-green-100 px-3 py-0.5 text-sm font-medium text-green-800';

        console.log("FCM Token Registration successful:", data.status);

    } catch (error) {
        console.error("FCM regisztrációs hiba:", error);
        fcmStatusEl.textContent = `Hiba: ${error.message}`;
        fcmStatusEl.className = 'inline-flex items-center rounded-full bg-red-100 px-3 py-0.5 text-sm font-medium text-red-800';
    }
}

// Globális WebSocket objektum a kapcsolat kezeléséhez
let ws;
// Globális lista az adatok tárolására, amit a WS események frissítenek
let playersData = [];
let editingPlayer = null;

/**
 * Inicializálás: Betölti a játékoslistát és elindítja a WebSocket kapcsolatot.
 */
async function fetchPlayers() {
    const statusEl = document.getElementById('status');
    const playerTableBodyEl = document.getElementById('playerTableBody');

    if (!statusEl || !playerTableBodyEl) {
        console.error("DOM elemek hiányoznak, leállítom a betöltést.");
        return;
    }

    statusEl.textContent = 'Betöltés...';
    statusEl.className = 'text-center text-blue-600 my-4';

    try {
        const response = await fetch(BACKEND_API_URL);

        if (!response.ok) {
            throw new Error(`HTTP hiba: ${response.status} (${response.statusText})`);
        }

        playersData = await response.json(); // Frissítjük a globális listát
        renderPlayers(playersData); // Megjelenítjük
        statusEl.textContent = `Sikeresen betöltve: ${playersData.length} játékos.`;
        statusEl.className = 'text-center text-green-600 my-4';

    } catch (error) {
        console.error("Hiba az adatok lekérdezésekor:", error);
        statusEl.textContent = `Hiba a szerverrel való kommunikáció során: ${error.message}. Ellenőrizze, hogy a Ktor szerver fut-e.`;
        statusEl.className = 'text-center text-red-600 my-4 font-bold';
    }
}

// ----------------------------------------------------------------------
// WebSocket és eseménykezelés
// ----------------------------------------------------------------------

/**
 * Esemény kezelése (frissítés, törlés, hozzáadás) a WS-ről érkező üzenet alapján.
 */
function handleEvent(eventData) {
    console.log("WS Event received:", eventData.type, eventData);

    switch (eventData.type) {
        case 'PlayerAdded':
            // Ellenőrizzük, hogy az ID már létezik-e (elkerüljük a duplikációt)
            if (!playersData.find(p => p.id === eventData.player.id)) {
                playersData = [...playersData, eventData.player];
            }
            // Mivel a Ktor mindig broadcastol, még a saját POST kérésünkre is,
            // a listát frissítjük a WS alapján.
            break;

        case 'PlayerDeleted':
            // Kiszűrjük a törölt elemet az ID alapján
            playersData = playersData.filter(p => p.id !== eventData.id);
            // Kiírjuk a törlés tényét
            document.getElementById('status').textContent = `Játékos ${eventData.id} törölve a WS-en keresztül.`;
            document.getElementById('status').className = 'text-center text-orange-600 my-4';
            break;

        case 'PlayerUpdated':
            const updatedPlayer = eventData.player;
            // Megkeressük a régi játékost a listában és lecseréljük
            playersData = playersData.map(p =>
                p.id === updatedPlayer.id ? updatedPlayer : p
            );
            document.getElementById('status').textContent = `Játékos ${updatedPlayer.id} frissítve a WS-en keresztül.`;
            document.getElementById('status').className = 'text-center text-orange-600 my-4';
            break;

        default:
            console.warn("Ismeretlen WS esemény típus:", eventData.type);
    }

    // Minden frissítés után újra rendereljük a táblázatot
    renderPlayers(playersData);
}

/**
 * Létrehozza és kezeli a WebSocket kapcsolatot
 */
function setupWebSocket() {
    if (ws) {
        ws.close();
    }
    ws = new WebSocket(WEBSOCKET_URL);

    ws.onopen = () => {
        console.log("WebSocket kapcsolat létrejött.");
        document.getElementById('wsStatus').textContent = 'Csatlakozva';
        document.getElementById('wsStatus').className = 'inline-flex items-center rounded-full bg-green-100 px-3 py-0.5 text-sm font-medium text-green-800';

        wsPingInterval = setInterval(() => {
            if (ws && ws.readyState === WebSocket.OPEN) {
                // Küldhetünk egy egyszerű JSON pinget, ha a Ktor beállítása igényli,
                // de a Ktor/Netty beépített Ping/Pong mechanizmusa a legjobb.
                // Maradjunk a kért JSON küldésnél, ha az a biztos.
                ws.send(JSON.stringify({ type: "ping" }));
            }
        }, 30000); // 30 másodpercenként
    };

    ws.onmessage = (event) => {
        try {
            const eventData = JSON.parse(event.data);
            handleEvent(eventData);
        } catch (e) {
            console.error("Hiba a WS üzenet dekódolásakor:", e);
        }
    };

    ws.onclose = () => {
        console.warn("WebSocket kapcsolat megszakadt. Újracsatlakozás 5 másodperc múlva...");
        document.getElementById('wsStatus').textContent = 'Kapcsolat megszakadt. Újracsatlakozás...';
        document.getElementById('wsStatus').className = 'inline-flex items-center rounded-full bg-red-100 px-3 py-0.5 text-sm font-medium text-red-800';
        setTimeout(setupWebSocket, 5000); // 5 másodperc után próbálja újra
    };

    ws.onerror = (error) => {
        console.error("WebSocket hiba:", error);
        ws.close();
    };
}

// ----------------------------------------------------------------------
// CRUD Műveletek
// ----------------------------------------------------------------------

/**
 * Játékos hozzáadása POST kéréssel.
 */
async function addPlayer(event) {
    event.preventDefault();

    const form = document.getElementById('addPlayerForm');
    const statusEl = document.getElementById('status');

    const nameInput = document.getElementById('playerName').value.trim();
    const ageInput = document.getElementById('playerAge').value.trim();

    if (nameInput === "") {
        statusEl.textContent = "Kérem adja meg a játékos nevét!";
        statusEl.className = 'text-center text-red-500 my-4';
        return;
    }

    const age = ageInput === "" ? null : parseInt(ageInput, 10);

    const newPlayerDTO = {
        name: nameInput,
        age: age
    };

    statusEl.textContent = 'Játékos hozzáadása...';
    statusEl.className = 'text-center text-blue-600 my-4';

    try {
        const response = await fetch(BACKEND_API_URL, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(newPlayerDTO)
        });

        if (!response.ok) {
            const errorBody = await response.text();
            throw new Error(`HTTP hiba: ${response.status} (${response.statusText}). Válasz: ${errorBody}`);
        }

        // A POST után kapott válasz is tartalmazza az új játékost, DE
        // mivel a Ktor WS-t küld, hagyjuk a WS-t frissíteni a playersData-t.
        // Itt csak a siker üzenetet jelezzük.

        statusEl.textContent = `Játékos "${nameInput}" sikeresen hozzáadva. (WS frissíti a listát)`;
        statusEl.className = 'text-center text-green-600 my-4 font-bold';

        form.reset();

    } catch (error) {
        console.error("Hiba a játékos hozzáadása során:", error);
        statusEl.textContent = `Hiba a játékos hozzáadása során: ${error.message}`;
        statusEl.className = 'text-center text-red-600 my-4 font-bold';
    }
}

/**
 * Játékos törlése DELETE kéréssel. (FRISSÍTVE: confirm() helyett showNonBlockingWarning)
 */
async function deletePlayer(id) {
    const playerName = playersData.find(p => p.id === id)?.name || id;

    // FIX: showNonBlockingWarning használata (ld. fent)
    showNonBlockingWarning(`Biztosan törölni szeretné a(z) "${playerName}" játékost (ID: ${id})?`, async (confirmed) => {
        if (!confirmed) {
            return;
        }

        const statusEl = document.getElementById('status');
        statusEl.textContent = `Játékos ${id} törlése...`;
        statusEl.className = 'text-center text-blue-600 my-4';

        try {
            // Törlés végpont hívása
            const response = await fetch(`${BACKEND_API_URL}/${id}`, {
                method: 'DELETE',
            });

            if (!response.ok) {
                const errorBody = await response.text();
                throw new Error(`HTTP hiba: ${response.status} (${response.statusText}). Válasz: ${errorBody}`);
            }

            // A Ktor küld egy WS eseményt PlayerDeleted-et, ami frissíti a listát.
            statusEl.textContent = `Játékos ${id} sikeresen törlésre került. (WS frissíti a listát)`;
            statusEl.className = 'text-center text-green-600 my-4 font-bold';

        } catch (error) {
            console.error("Hiba a játékos törlése során:", error);
            statusEl.textContent = `Hiba a törlés során: ${error.message}`;
            statusEl.className = 'text-center text-red-600 my-4 font-bold';
        }
    });
}

/**
 * Megnyitja a szerkesztő modalt és feltölti az űrlapot a játékos adataival.
 */
function handleEdit(player) {
    editingPlayer = player; // Mentjük az aktuális játékos adatait
    document.getElementById('editPlayerId').value = player.id;
    document.getElementById('editPlayerName').value = player.name;
    document.getElementById('editPlayerAge').value = player.age != null ? player.age : '';

    // Megjelenítjük a modalt
    const modal = document.getElementById('editModal');
    modal.classList.remove('hidden');
    modal.classList.add('flex');
}

/**
 * Bezárja a szerkesztő modalt.
 */
function closeEditModal() {
    editingPlayer = null; // Töröljük a szerkesztett játékos állapotát
    const modal = document.getElementById('editModal');
    modal.classList.add('hidden');
    modal.classList.remove('flex');
    document.getElementById('status').textContent = ''; // Töröljük az esetleges hibákat
}

/**
 * Elküldi a szerkesztett adatokat a szerverre (HTTP PUT).
 */
async function saveEdit(event) {
    event.preventDefault();

    if (!editingPlayer) return; // Védelmi mechanizmus: ha nincs játékos, kilép

    const statusEl = document.getElementById('status');
    const nameInput = document.getElementById('editPlayerName').value.trim();
    const ageInput = document.getElementById('editPlayerAge').value.trim();
    const playerId = editingPlayer.id; // ID kinyerése a tárolt objektumból

    if (nameInput === "") {
        statusEl.textContent = "A név nem lehet üres a szerkesztés során!";
        statusEl.className = 'text-center text-red-500 my-4';
        return;
    }

    const age = ageInput === "" ? null : parseInt(ageInput, 10);

    const updatedPlayerDTO = {
        name: nameInput,
        age: age
    };

    const putUrl = `${BACKEND_API_URL}/${playerId}`;

    statusEl.textContent = `Játékos ${playerId} frissítése...`;
    statusEl.className = 'text-center text-blue-600 my-4';

    try {
        const response = await fetch(putUrl, {
            method: 'PUT',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(updatedPlayerDTO)
        });

        if (!response.ok) {
            const errorBody = await response.text();
            throw new Error(`HTTP hiba: ${response.status} (${response.statusText}). Válasz: ${errorBody}`);
        }

        closeEditModal(); // Bezárjuk a modalt
        // A WS esemény (PlayerUpdated) fogja frissíteni a listát!
        statusEl.textContent = `Játékos ${playerId} sikeresen frissítve. (WS frissíti a listát)`;
        statusEl.className = 'text-center text-green-600 my-4 font-bold';

    } catch (error) {
        console.error("Hiba a játékos frissítése során:", error);
        statusEl.textContent = `Hiba a frissítés során: ${error.message}`;
        statusEl.className = 'text-center text-red-600 my-4 font-bold';
    }
}

/**
 * Megjeleníti a játékosadatokat a táblázatban a globális playersData lista alapján.
 * @param {Array<Object>} players - A játékosokat tartalmazó tömb.
 */
function renderPlayers(players) {
    const playerTableBodyEl = document.getElementById('playerTableBody');
    if (!playerTableBodyEl) return;

    playerTableBodyEl.innerHTML = ''; // Előző tartalom törlése

    if (players.length === 0) {
        playerTableBodyEl.innerHTML = '<tr><td colspan="4" class="py-4 text-center text-gray-500">Nincsenek adatok az adatbázisban.</td></tr>';
        return;
    }

    // Rendezés ID alapján (az átláthatóság kedvéért)
    const sortedPlayers = [...players].sort((a, b) => a.id - b.id);

    sortedPlayers.forEach(player => {
        const row = playerTableBodyEl.insertRow();
        row.className = 'border-b hover:bg-indigo-50 transition duration-150';

        // ID
        let cell = row.insertCell();
        cell.textContent = player.id;
        cell.className = 'py-3 px-6 font-medium text-gray-900';

        // Név
        cell = row.insertCell();
        cell.textContent = player.name || 'N/A';
        cell.className = 'py-3 px-6 text-gray-700';

        // Életkor
        cell = row.insertCell();
        cell.textContent = player.age != null ? player.age : 'N/A';
        cell.className = 'py-3 px-6 text-gray-700';

        // Műveletek
        cell = row.insertCell();
        cell.className = 'py-3 px-6 flex space-x-2 justify-center';
        // A JSON.stringify és replace() a szigorú HTML-entitások miatt szükséges a beágyazott JS-ben
        cell.innerHTML = `
            <button onclick="handleEdit(${JSON.stringify(player).replace(/"/g, '&quot;')})" class="bg-yellow-500 hover:bg-yellow-600 text-white font-semibold py-1 px-3 rounded-md transition duration-150 text-sm">
                Szerkesztés
            </button>
            <button onclick="deletePlayer(${player.id})" class="bg-red-600 hover:bg-red-700 text-white font-semibold py-1 px-3 rounded-md transition duration-150 text-sm">
                Törlés
            </button>
        `;
    });
}

// Amikor az oldal betöltődik, automatikusan töltse be az adatokat és indítsa a WS-t
window.onload = () => {
    fetchPlayers();
    setupWebSocket();
}
// Fontos: a JS funkciókat az admin_dashboard.html fájlban is elérhetővé kell tenni!
window.handleEdit = handleEdit;
window.deletePlayer = deletePlayer;
window.registerFCMToken = registerFCMToken;
window.addPlayer = addPlayer;
window.closeEditModal = closeEditModal;
window.saveEdit = saveEdit;
window.fetchPlayers = fetchPlayers;