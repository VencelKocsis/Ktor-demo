async function fetchPlayers() {
    try {
        const response = await fetch(BACKEND_API_URL);
        if (!response.ok) throw new Error(`Hiba: ${response.status}`);
        playersData = await response.json();

        const captainSelect = document.getElementById('teamCaptain');
        if (captainSelect) {
            captainSelect.innerHTML = `<option value="">${t('select_captain')}</option>`;
            playersData.forEach(player => {
                captainSelect.innerHTML += `<option value="${player.userId}">${player.name || 'Névtelen'}</option>`;
            });
        }
    } catch (error) {
        console.error("Nem sikerült lekérni a játékosokat a kapitány listához:", error);
    }
}

async function fetchTeams() {
    const container = document.getElementById('teamsContainer');
    if (!container) return;

    container.innerHTML = `<div class="col-span-full text-center py-8 text-indigo-600 dark:text-indigo-400 font-bold animate-pulse">${t('loading_teams')}</div>`;

    try {
        const response = await fetch(TEAMS_API_URL);
        if (!response.ok) throw new Error(`Hiba: ${response.status}`);
        teamsDataCache = await response.json();

        container.innerHTML = '';
        if (teamsDataCache.length === 0) {
            container.innerHTML = `<div class="col-span-full text-center py-8 text-slate-500 dark:text-slate-400">${t('no_teams')}</div>`;
            return;
        }

        teamsDataCache.forEach(team => {
            container.innerHTML += `
                <div class="bg-slate-50 dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-5 hover:shadow-md transition-all relative">
                    <div class="absolute top-4 right-4 flex gap-2">
                        <button onclick="openEditTeam(${team.teamId})" class="text-indigo-600 dark:text-indigo-400 hover:text-indigo-800 dark:hover:text-indigo-300 text-sm font-bold bg-indigo-100 dark:bg-indigo-900/30 px-3 py-1 rounded-md transition-colors">${t('edit')}</button>
                        <button onclick="deleteTeam(${team.teamId})" class="text-rose-600 dark:text-rose-400 hover:text-rose-800 dark:hover:text-rose-300 text-sm font-bold bg-rose-100 dark:bg-rose-900/30 px-3 py-1 rounded-md transition-colors">${t('delete')}</button>
                    </div>
                    <div class="flex justify-between items-start mb-4 pr-20">
                        <div>
                            <h3 class="text-xl font-extrabold text-slate-800 dark:text-white">${team.teamName}</h3>
                            <p class="text-sm font-semibold text-indigo-600 dark:text-indigo-400">${team.clubName} • ${team.division || 'N/A'}</p>
                        </div>
                    </div>
                    <div class="grid grid-cols-3 gap-2 mb-4 text-center text-sm border-t border-b border-slate-200 dark:border-slate-700 py-3">
                        <div><span class="block text-slate-500 dark:text-slate-400 text-xs uppercase font-bold">${t('wins')}</span><span class="font-bold text-emerald-600 dark:text-emerald-400">${team.wins}</span></div>
                        <div class="border-l border-r border-slate-200 dark:border-slate-700"><span class="block text-slate-500 dark:text-slate-400 text-xs uppercase font-bold">${t('draws')}</span><span class="font-bold text-amber-500 dark:text-amber-400">${team.draws}</span></div>
                        <div><span class="block text-slate-500 dark:text-slate-400 text-xs uppercase font-bold">${t('losses')}</span><span class="font-bold text-rose-600 dark:text-rose-400">${team.losses}</span></div>
                    </div>
                    <div>
                        <h4 class="text-xs font-bold text-slate-500 dark:text-slate-400 uppercase mb-2">${t('members')} (${team.members.length})</h4>
                        <div class="flex flex-wrap gap-1.5">
                            ${team.members.map(m => `<span class="inline-block bg-white dark:bg-slate-700 border border-slate-200 dark:border-slate-600 text-slate-700 dark:text-slate-200 text-xs px-2 py-1 rounded-md shadow-sm ${m.isCaptain ? 'ring-1 ring-amber-400 dark:ring-amber-500 font-bold' : ''}">${m.isCaptain ? '⭐ ' : ''}${m.name}</span>`).join('')}
                        </div>
                    </div>
                </div>
            `;
        });

        if (typeof updateMatchTeamDropdowns === "function") updateMatchTeamDropdowns();

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
    if (division.length > 0) newTeamData.division = division;

    try {
        const response = await fetch(TEAMS_API_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(newTeamData)
        });

        if (!response.ok) throw new Error(await response.text() || `Hiba: ${response.status}`);
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

    const container = document.getElementById('editTeamMembersContainer');
    container.innerHTML = '';
    const capSelect = document.getElementById('editTeamCaptain');
    capSelect.innerHTML = '';

    const playerMap = new Map();
    team.members.forEach(m => playerMap.set(m.userId, m));
    playersData.forEach(p => playerMap.set(p.userId, p));

    const combinedPlayers = Array.from(playerMap.values());

    combinedPlayers.forEach(p => {
        const isMember = team.members.some(m => m.userId === p.userId);
        const isCaptain = team.members.some(m => m.userId === p.userId && m.isCaptain);

        container.innerHTML += `
            <label class="flex items-center gap-2 cursor-pointer dark:text-white text-sm">
                <input type="checkbox" name="editTeamMembers" value="${p.userId}" ${isMember ? 'checked' : ''} class="w-4 h-4 text-indigo-600 rounded">
                ${p.name || 'Névtelen'}
            </label>
        `;
        capSelect.innerHTML += `<option value="${p.userId}" ${isCaptain ? 'selected' : ''}>${p.name || 'Névtelen'}</option>`;
    });

    const modal = document.getElementById('editTeamModal');
    modal.classList.remove('hidden');
    modal.classList.add('flex');
}

async function saveTeamEdit(event) {
    event.preventDefault();
    const id = document.getElementById('editTeamId').value;
    const divisionVal = document.getElementById('editTeamDivision').value.trim();

    const selectedCheckboxes = document.querySelectorAll('input[name="editTeamMembers"]:checked');
    const memberIds = Array.from(selectedCheckboxes).map(cb => parseInt(cb.value, 10));

    const captainUserId = parseInt(document.getElementById('editTeamCaptain').value, 10);

    if (isNaN(captainUserId)) {
        showStatus("Hiba: Nincs kiválasztva csapatkapitány!", true);
        return;
    }

    if (!memberIds.includes(captainUserId)) {
        showStatus("A kiválasztott csapatkapitánynak csapattagnak (bepipálva) is kell lennie!", true);
        return;
    }

    const updateData = {
        name: document.getElementById('editTeamName').value.trim(),
        division: divisionVal.length > 0 ? divisionVal : null,
        captainUserId: captainUserId,
        memberIds: memberIds
    };

    try {
        const response = await fetch(`${TEAMS_API_URL}/${id}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(updateData)
        });

        if (!response.ok) {
            const text = await response.text();
            throw new Error(text || 'Hiba a frissítésnél');
        }

        closeEditTeamModal();
        showStatus('Csapat sikeresen frissítve!');

        fetchTeams();
        fetchPlayers();
    } catch (error) {
        showStatus(error.message, true);
    }
}

function closeEditTeamModal() {
    const modal = document.getElementById('editTeamModal');
    modal.classList.add('hidden');
    modal.classList.remove('flex');
}

async function deleteTeam(id) {
    if (!confirm("Biztosan törölni szeretnéd ezt a csapatot? (A hozzá tartozó meccseket előbb törölni kell!)")) return;
    try {
        const response = await fetch(`${TEAMS_API_URL}/${id}`, { method: 'DELETE' });
        if (!response.ok) {
            const text = await response.text();
            throw new Error(text || 'Hiba a törlésnél');
        }
        showStatus('Csapat sikeresen törölve!');
        fetchTeams();
    } catch (error) {
        showStatus(error.message, true);
    }
}