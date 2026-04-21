function updateMatchTeamDropdowns() {
    const homeSelect = document.getElementById('matchHomeTeam');
    const guestSelect = document.getElementById('matchGuestTeam');

    if (homeSelect && guestSelect && teamsDataCache.length > 0) {
        homeSelect.innerHTML = `<option value="">${t('home_team_ph')}</option>`;
        guestSelect.innerHTML = `<option value="">${t('guest_team_ph')}</option>`;

        teamsDataCache.forEach(team => {
            homeSelect.innerHTML += `<option value="${team.teamId}">${team.teamName}</option>`;
            guestSelect.innerHTML += `<option value="${team.teamId}">${team.teamName}</option>`;
        });
    }
}

async function fetchSeasons() {
    try {
        const response = await fetch(SEASONS_API_URL);
        if (!response.ok) throw new Error("Hiba a szezonok betöltésekor");
        const seasons = await response.json();

        seasonsDataCache = seasons;

        const seasonSelect = document.getElementById('matchSeason');
        if (seasonSelect) {
            seasonSelect.innerHTML = `<option value="">${t('select_season')}</option>`;
            seasons.forEach(s => {
                const selected = s.isActive ? 'selected' : '';
                seasonSelect.innerHTML += `<option value="${s.id}" ${selected}>${s.name}</option>`;
            });
        }

        if (typeof updateLeaderboardFilters === 'function') updateLeaderboardFilters();

    } catch (error) {
        console.error(error);
    }
}

async function fetchMatches() {
    const container = document.getElementById('matchesContainer');
    if (!container) return;
    container.innerHTML = `<div class="text-center py-4 text-slate-500">${t('loading_matches')}</div>`;

    try {
        const response = await fetch(MATCHES_API_URL);
        if (!response.ok) throw new Error("Hiba a meccsek lekérésénél");
        const matches = await response.json();

        matchesDataCache = matches;

        if (typeof renderLeaderboard === 'function') renderLeaderboard();

        container.innerHTML = '';
        if (matches.length === 0) {
            container.innerHTML = `<div class="text-center py-4 text-slate-500">${t('no_matches')}</div>`;
            return;
        }

        matches.sort((a, b) => b.id - a.id).forEach(match => {
            let statusBadge = '';
            if (match.status === 'scheduled') statusBadge = `<span class="bg-blue-100 text-blue-800 text-xs font-bold px-2 py-1 rounded">${t('status_scheduled')}</span>`;
            else if (match.status === 'in_progress') statusBadge = `<span class="bg-amber-100 text-amber-800 text-xs font-bold px-2 py-1 rounded">${t('status_live')}</span>`;
            else statusBadge = `<span class="bg-emerald-100 text-emerald-800 text-xs font-bold px-2 py-1 rounded">${t('status_finished')}</span>`;

            const dateObj = new Date(match.date);
            const formattedDate = dateObj.toLocaleString('hu-HU', { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute:'2-digit' });

            container.innerHTML += `
                <div class="bg-slate-50 dark:bg-slate-700 rounded-xl border border-slate-200 dark:border-slate-600 p-4 flex justify-between items-center">
                    <div>
                        <div class="flex items-center gap-2 mb-1">
                            <span class="text-xs font-bold text-slate-500 dark:text-slate-400 uppercase">${match.roundNumber}. ${t('round')}</span>
                            ${statusBadge}
                        </div>
                        <h3 class="text-lg font-bold text-slate-800 dark:text-white">
                            ${match.homeTeamName} <span class="text-slate-400">vs</span> ${match.guestTeamName}
                        </h3>
                        <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">
                            📅 ${formattedDate} | 📍 ${match.location}
                        </p>
                    </div>
                    <div class="text-right flex flex-col items-end">
                        <div class="text-2xl font-black ${match.status === 'finished' ? 'text-indigo-600 dark:text-indigo-400' : 'text-slate-700 dark:text-slate-300'}">
                            ${match.status === 'scheduled' ? '- : -' : `${match.homeScore} : ${match.guestScore}`}
                        </div>
                        <button onclick="deleteMatch(${match.id})" class="mt-2 text-rose-600 dark:text-rose-400 hover:text-rose-800 dark:hover:text-rose-300 text-xs font-bold px-2 py-1 bg-rose-50 dark:bg-rose-900/30 rounded-md transition-colors">${t('delete')}</button>
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

    const rawMatchDate = document.getElementById('matchDate').value;
    const location = document.getElementById('matchLocation').value.trim();

    if (!seasonId || !roundNumber || !homeTeamId || !guestTeamId || !rawMatchDate || !location) {
        showStatus("Minden mező kitöltése kötelező!", true);
        return;
    }

    if (homeTeamId === guestTeamId) {
        showStatus("A hazai és vendég csapat nem lehet ugyanaz!", true);
        return;
    }

    const newMatchData = {
        seasonId: parseInt(seasonId, 10),
        roundNumber: parseInt(roundNumber, 10),
        homeTeamId: parseInt(homeTeamId, 10),
        guestTeamId: parseInt(guestTeamId, 10),
        matchDate: rawMatchDate,
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
        showStatus("Ktor Backend hiba: " + error.message, true);
    }
}

async function deleteMatch(id) {
    if (!confirm("Biztosan törölni szeretnéd ezt a mérkőzést? Minden kapcsolódó adat elvész!")) return;
    try {
        const response = await fetch(`${MATCHES_API_URL}/${id}`, { method: 'DELETE' });
        if (!response.ok) {
            const text = await response.text();
            throw new Error(text || 'Hiba a törlésnél');
        }
        showStatus('Mérkőzés sikeresen törölve!');
        fetchMatches();
    } catch (error) {
        showStatus(error.message, true);
    }
}