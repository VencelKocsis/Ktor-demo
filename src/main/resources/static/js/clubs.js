async function fetchClubs() {
    try {
        const response = await fetch(CLUBS_API_URL);
        if (!response.ok) throw new Error("Hiba a klubok lekérésénél.");

        clubsDataCache = await response.json();

        const clubSelect = document.getElementById('teamClub');
        if (clubSelect) {
            clubSelect.innerHTML = `<option value="">${t('select_club')}</option>`;
            clubsDataCache.forEach(club => {
                clubSelect.innerHTML += `<option value="${club.id}">${club.name}</option>`;
            });
        }

        const container = document.getElementById('clubsContainer');
        if (container) {
            container.innerHTML = '';
            if (clubsDataCache.length === 0) {
                container.innerHTML = `<div class="col-span-full text-center py-4 text-slate-500 dark:text-slate-400">${t('no_clubs')}</div>`;
                return;
            }

            clubsDataCache.forEach(club => {
                container.innerHTML += `
                    <div class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-4 shadow-sm flex justify-between items-center transition-colors">
                        <div>
                            <h3 class="font-bold text-slate-800 dark:text-white">${club.name}</h3>
                            <p class="text-xs text-slate-500 dark:text-slate-400">${club.address}</p>
                        </div>
                        <div class="flex gap-2">
                            <button onclick="openEditClub(${club.id})" class="text-emerald-600 dark:text-emerald-400 hover:text-emerald-800 dark:hover:text-emerald-300 text-sm font-bold bg-emerald-50 dark:bg-emerald-900/30 px-3 py-1.5 rounded-md transition-colors">${t('edit')}</button>
                            <button onclick="deleteClub(${club.id})" class="text-rose-600 dark:text-rose-400 hover:text-rose-800 dark:hover:text-rose-300 text-sm font-bold bg-rose-50 dark:bg-rose-900/30 px-3 py-1.5 rounded-md transition-colors">${t('delete')}</button>
                        </div>
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

    if (!id) {
        showStatus('Hiba: Hiányzó klub ID! (Ellenőrizd a HTML-t)', true);
        return;
    }

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

        if (typeof fetchTeams === 'function') fetchTeams();
    } catch (error) {
        showStatus(error.message, true);
    }
}

async function deleteClub(id) {
    if (!confirm("Biztosan törölni szeretnéd ezt a klubot? (A hozzá tartozó csapatokat előbb törölni kell!)")) return;
    try {
        const response = await fetch(`${CLUBS_API_URL}/${id}`, { method: 'DELETE' });
        if (!response.ok) {
            const text = await response.text();
            throw new Error(text || 'Hiba a törlésnél');
        }
        showStatus('Klub sikeresen törölve!');
        fetchClubs();
        if (typeof fetchTeams === 'function') fetchTeams();
    } catch (error) {
        showStatus(error.message, true);
    }
}