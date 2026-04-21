// ==========================================
// SZÓTÁR (Magyar / Angol)
// ==========================================
const translations = {
    hu: {
        "title": "Klub Adminisztráció",
        "tab_teams": "Csapatok & Ligák",
        "tab_matches": "Mérkőzések",
        "tab_system": "Rendszer & Értesítések",

        "new_team_title": "Új Csapat Létrehozása",
        "club_label": "Klub*",
        "team_name_label": "Csapat Név*",
        "division_label": "Divízió / Osztály",
        "captain_label": "Csapatkapitány*",
        "save_btn": "Mentés",

        "new_club_title": "Új Klub Létrehozása",
        "club_name_label": "Klub Neve*",
        "address_label": "Cím*",
        "save_club_btn": "Klub Mentése",

        "teams_title": "Csapatok",
        "clubs_title": "Klubok",
        "refresh_btn": "Frissítés",

        "new_match_title": "Új Mérkőzés Kiírása",
        "season_label": "Szezon*",
        "round_label": "Kör (Forduló)*",
        "home_team_label": "Hazai Csapat*",
        "guest_team_label": "Vendég Csapat*",
        "datetime_label": "Dátum és Időpont*",
        "location_label": "Helyszín*",
        "create_match_btn": "Mérkőzés Létrehozása",

        "scheduled_matches_title": "Kiírt Mérkőzések",

        "fcm_title": "Push Értesítések Tesztelése (FCM)",
        "fcm_desc": "A Firebase Cloud Messaging (FCM) rendszer tesztelése. Ha rányomsz, regisztrál egy dummy tokent a böngészőhöz.",
        "send_token_btn": "Teszt Token Küldése",
        "waiting": "Várakozás...",

        "ws_connecting": "WS Csatlakozás...",
        "ws_active": "WS Aktív",
        "ws_disconnected": "WS Bontva",

        "tab_leaderboard": "Ranglista",
        "leaderboard_title": "Bajnokság Állása",
        "team": "Csapat",
        "matches_played": "M",
        "v_d_l": "Gy - D - V",
        "points": "Pont",
        "all_divisions": "Összes Divízió",
        "no_teams_in_division": "Nincs megjeleníthető csapat ebben a divízióban.",
        "all_seasons": "Összes Szezon",
        "no_more_teams": "Nincs több megjeleníthető csapat.",

        // ---Dinamikus JS tartalmakhoz ---
        "select_club": "Válassz klubot...",
        "no_clubs": "Még nincsenek klubok.",
        "edit": "Módosít",
        "delete": "Törlés", // <--- ÚJ
        "select_captain": "Kapitány kiválasztása...",
        "loading_teams": "Csapatok betöltése...",
        "no_teams": "Nincsenek regisztrált csapatok.",
        "wins": "Győzelem",
        "draws": "Döntetlen",
        "losses": "Vereség",
        "members": "Csapattagok",
        "select_season": "Szezon kiválasztása...",
        "home_team_ph": "Hazai csapat...",
        "guest_team_ph": "Vendég csapat...",
        "loading_matches": "Mérkőzések betöltése...",
        "no_matches": "Nincsenek kiírt mérkőzések.",
        "status_scheduled": "Kiírva",
        "status_live": "Élő",
        "status_finished": "Befejezve",
        "round": "Kör",
        "sending": "Küldés...",
        "fcm_success": "Sikeres Teszt (Mock Token)",
        "fcm_error": "Hiba történt"
    },
    en: {
        "title": "Club Administration",
        "tab_teams": "Teams & Leagues",
        "tab_matches": "Matches",
        "tab_system": "System & Notifications",

        "new_team_title": "Create New Team",
        "club_label": "Club*",
        "team_name_label": "Team Name*",
        "division_label": "Division / League",
        "captain_label": "Team Captain*",
        "save_btn": "Save",

        "new_club_title": "Create New Club",
        "club_name_label": "Club Name*",
        "address_label": "Address*",
        "save_club_btn": "Save Club",

        "teams_title": "Teams",
        "clubs_title": "Clubs",
        "refresh_btn": "Refresh",

        "new_match_title": "Schedule New Match",
        "season_label": "Season*",
        "round_label": "Round*",
        "home_team_label": "Home Team*",
        "guest_team_label": "Guest Team*",
        "datetime_label": "Date and Time*",
        "location_label": "Location*",
        "create_match_btn": "Create Match",

        "scheduled_matches_title": "Scheduled Matches",

        "fcm_title": "Test Push Notifications (FCM)",
        "fcm_desc": "Testing the Firebase Cloud Messaging (FCM) system. Clicking this registers a dummy token to the browser.",
        "send_token_btn": "Send Test Token",
        "waiting": "Waiting...",

        "ws_connecting": "WS Connecting...",
        "ws_active": "WS Active",
        "ws_disconnected": "WS Disconnected",

        "tab_leaderboard": "Leaderboard",
        "leaderboard_title": "Championship Standings",
        "team": "Team",
        "matches_played": "P",
        "v_d_l": "W - D - L",
        "points": "PTS",
        "all_divisions": "All Divisions",
        "no_teams_in_division": "No teams to display in this division.",
        "all_seasons": "All Seasons",
        "no_more_teams": "No more teams to display.",

        // --- For dynamic JS content ---
        "select_club": "Select a club...",
        "no_clubs": "No clubs yet.",
        "edit": "Edit",
        "delete": "Delete",
        "select_captain": "Select captain...",
        "loading_teams": "Loading teams...",
        "no_teams": "No registered teams.",
        "wins": "Wins",
        "draws": "Draws",
        "losses": "Losses",
        "members": "Members",
        "select_season": "Select season...",
        "home_team_ph": "Home team...",
        "guest_team_ph": "Guest team...",
        "loading_matches": "Loading matches...",
        "no_matches": "No scheduled matches.",
        "status_scheduled": "Scheduled",
        "status_live": "Live",
        "status_finished": "Finished",
        "round": "Round",
        "sending": "Sending...",
        "fcm_success": "Success (Mock Token)",
        "fcm_error": "Error occurred"
    }
};

let currentLang = localStorage.getItem('lang') || 'hu';

function setLanguage(lang) {
    currentLang = lang;
    localStorage.setItem('lang', lang);

    document.getElementById('lang-hu').classList.toggle('opacity-50', lang !== 'hu');
    document.getElementById('lang-en').classList.toggle('opacity-50', lang !== 'en');

    document.querySelectorAll('[data-i18n]').forEach(element => {
        const key = element.getAttribute('data-i18n');
        if (translations[lang][key]) {
            element.textContent = translations[lang][key];
        }
    });

    if (typeof fetchClubs === 'function') fetchClubs();
    if (typeof fetchTeams === 'function') fetchTeams();
    if (typeof fetchMatches === 'function') fetchMatches();
}

function t(key) {
    return translations[currentLang][key] || key;
}

document.addEventListener('DOMContentLoaded', () => {
    setLanguage(currentLang);
});