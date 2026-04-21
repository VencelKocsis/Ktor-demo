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
        if (typeof updateLeaderboardFilters === "function") updateLeaderboardFilters();
        if (typeof renderLeaderboard === "function") renderLeaderboard();

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

// RANGLISTA (LEADERBOARD) LOGIKA & PÓDIUM

function updateLeaderboardFilters() {
    const divFilter = document.getElementById('leaderboardDivisionFilter');
    const seasonFilter = document.getElementById('leaderboardSeasonFilter');

    if (divFilter && teamsDataCache) {
        const currentDiv = divFilter.value;
        const divisions = [...new Set(teamsDataCache.map(t => t.division).filter(d => d))].sort();
        divFilter.innerHTML = `<option value="">${t('all_divisions')}</option>`;
        divisions.forEach(d => {
            divFilter.innerHTML += `<option value="${d}" ${d === currentDiv ? 'selected' : ''}>${d}</option>`;
        });
        divFilter.value = currentDiv;
    }

    if (seasonFilter && seasonsDataCache) {
        const activeSeasonId = seasonsDataCache.find(s => s.isActive)?.id?.toString();
        const currentSeason = seasonFilter.value || activeSeasonId || '';

        seasonFilter.innerHTML = `<option value="">${t('all_seasons')}</option>`;
        seasonsDataCache.forEach(s => {
            seasonFilter.innerHTML += `<option value="${s.id}" ${s.id.toString() === currentSeason ? 'selected' : ''}>${s.name}</option>`;
        });

        seasonFilter.value = currentSeason;
    }
}

function calculateDynamicStats(seasonIdFilter) {
    // 1. Alapértelmezett, nulla statisztika minden csapatnak
    const statsMap = {};
    teamsDataCache.forEach(t => {
        statsMap[t.teamId] = { ...t, matchesPlayed: 0, wins: 0, draws: 0, losses: 0, points: 0 };
    });

    // 2. Kiszűrjük a befejezett meccseket, és rászűrünk a szezonra, ha van
    let validMatches = matchesDataCache.filter(m => m.status === 'finished');
    if (seasonIdFilter) {
        validMatches = validMatches.filter(m => m.seasonId.toString() === seasonIdFilter.toString());
    }

    // 3. Pontszámítás (Győzelem = 2 pont, Döntetlen = 1 pont)
    validMatches.forEach(m => {
        const home = statsMap[m.homeTeamId];
        const guest = statsMap[m.guestTeamId];
        if (!home || !guest) return;

        home.matchesPlayed++;
        guest.matchesPlayed++;

        if (m.homeScore > m.guestScore) {
            home.wins++; home.points += 2;
            guest.losses++;
        } else if (m.homeScore < m.guestScore) {
            guest.wins++; guest.points += 2;
            home.losses++;
        } else {
            home.draws++; home.points += 1;
            guest.draws++; guest.points += 1;
        }
    });

    return Object.values(statsMap);
}

function createPodiumItem(team, rank, heightClass, colorClass, gradientClass) {
    if (!team) {
        return `<div class="flex flex-col items-center w-1/3 max-w-[140px]">
                    <div class="text-slate-400 font-bold mb-2">-</div>
                    <div class="w-full ${heightClass} ${gradientClass} rounded-t-xl opacity-20"></div>
                </div>`;
    }
    return `
    <div class="flex flex-col items-center w-1/3 max-w-[140px] transform transition-transform hover:-translate-y-2 group">
        <div class="text-center mb-3">
            <div class="font-extrabold text-slate-800 dark:text-white truncate w-full px-1 text-sm sm:text-base" title="${team.teamName}">${team.teamName}</div>
            <div class="font-black ${colorClass} text-lg sm:text-xl drop-shadow-sm">${team.points} <span class="text-xs">PTS</span></div>
        </div>
        <div class="w-full ${heightClass} ${gradientClass} shadow-xl rounded-t-xl flex justify-center items-start pt-4 relative overflow-hidden border-t border-white/30">
            <div class="absolute inset-0 bg-gradient-to-b from-white/20 to-transparent"></div>
            <span class="text-5xl font-black text-white/90 drop-shadow-md z-10">${rank}</span>
        </div>
    </div>`;
}

function renderLeaderboard() {
    const tbody = document.getElementById('leaderboardTbody');
    const podiumContainer = document.getElementById('podiumContainer');
    const divisionFilter = document.getElementById('leaderboardDivisionFilter')?.value;
    const seasonFilter = document.getElementById('leaderboardSeasonFilter')?.value;

    if (!tbody || !podiumContainer || !teamsDataCache) return;

    // 1. Dinamikus pontszámítás
    let filteredTeams = calculateDynamicStats(seasonFilter);

    // 2. Divízió szűrés
    if (divisionFilter) {
        filteredTeams = filteredTeams.filter(t => t.division === divisionFilter);
    }

    // 3. Rendezés (Pontszám szerint csökkenő, majd Győzelmek száma szerint)
    filteredTeams.sort((a, b) => {
        if (b.points !== a.points) return b.points - a.points;
        return b.wins - a.wins;
    });

    // === PÓDIUM RENDERELÉS ===
    const top3 = filteredTeams.slice(0, 3);

    // Ezüst (2. hely), Arany (1. hely), Bronz (3. hely) elrendezés
    podiumContainer.innerHTML = `
        ${createPodiumItem(top3[1], 2, 'h-32', 'text-slate-500 dark:text-slate-300', 'bg-gradient-to-b from-slate-300 to-slate-500 dark:from-slate-500 dark:to-slate-700')}
        ${createPodiumItem(top3[0], 1, 'h-44', 'text-amber-500 dark:text-amber-400', 'bg-gradient-to-b from-yellow-300 to-yellow-600 dark:from-yellow-400 dark:to-yellow-700')}
        ${createPodiumItem(top3[2], 3, 'h-24', 'text-amber-700 dark:text-amber-600', 'bg-gradient-to-b from-amber-600 to-amber-800 dark:from-amber-700 dark:to-amber-900')}
    `;

    // === TÁBLÁZAT RENDERELÉS (4. Helytől) ===
    tbody.innerHTML = '';
    const remainingTeams = filteredTeams.slice(3);

    if (remainingTeams.length === 0) {
        tbody.innerHTML = `<tr><td colspan="5" class="px-6 py-8 text-center text-slate-400 font-medium">${t('no_more_teams')}</td></tr>`;
        return;
    }

    remainingTeams.forEach((team, index) => {
        const actualRank = index + 4; // Mivel a top 3 le van vágva
        tbody.innerHTML += `
            <tr class="hover:bg-slate-50 dark:hover:bg-slate-700/30 transition-colors">
                <td class="px-6 py-4 font-bold text-slate-500 dark:text-slate-400">${actualRank}.</td>
                <td class="px-6 py-4">
                    <div class="font-bold text-slate-800 dark:text-white text-base">${team.teamName}</div>
                    <div class="text-xs text-slate-500 dark:text-slate-400 mt-0.5">${team.clubName}</div>
                </td>
                <td class="px-6 py-4 text-center font-bold text-slate-700 dark:text-slate-300">${team.matchesPlayed}</td>
                <td class="px-6 py-4 text-center text-sm font-bold text-slate-600 dark:text-slate-300 tracking-wide">
                    <span class="text-emerald-600 dark:text-emerald-400">${team.wins}</span> -
                    <span class="text-amber-500 dark:text-amber-400">${team.draws}</span> -
                    <span class="text-rose-600 dark:text-rose-400">${team.losses}</span>
                </td>
                <td class="px-6 py-4 text-right text-lg font-black text-indigo-600 dark:text-indigo-400">${team.points}</td>
            </tr>
        `;
    });
}