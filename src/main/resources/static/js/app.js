async function registerFCMToken() {
    const fcmStatusEl = document.getElementById('fcmStatus');
    if (!fcmStatusEl) return;
    fcmStatusEl.textContent = t('sending');
    fcmStatusEl.className = 'text-sm font-semibold text-amber-500';

    try {
        const response = await fetch(FCM_REGISTRATION_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ fcm_token: PLACEHOLDER_FCM_TOKEN })
        });
        if (!response.ok) throw new Error('Regisztrációs hiba');
        fcmStatusEl.textContent = t('fcm_success');
        fcmStatusEl.className = 'text-sm font-semibold text-emerald-600';
    } catch (error) {
        fcmStatusEl.textContent = t('fcm_error');
        fcmStatusEl.className = 'text-sm font-semibold text-rose-600';
    }
}

// Inicializálás az oldal betöltésekor
window.onload = () => {
    fetchClubs();
    fetchTeams();
    fetchPlayers();
    fetchSeasons();
    fetchMatches();
    switchTab('teamsTab');
};

// ==========================================
// GLOBÁLIS BINDINGOK A HTML-HEZ
// ==========================================
window.toggleDarkMode = toggleDarkMode;
window.switchTab = switchTab;
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