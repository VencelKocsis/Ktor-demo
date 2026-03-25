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
        "ws_disconnected": "WS Bontva"
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
        "ws_disconnected": "WS Disconnected"
    }
};

// ==========================================
// I18N LOGIKA
// ==========================================
let currentLang = localStorage.getItem('lang') || 'hu';

function setLanguage(lang) {
    currentLang = lang;
    localStorage.setItem('lang', lang);

    // Gombok stílusának frissítése
    document.getElementById('lang-hu').classList.toggle('opacity-50', lang !== 'hu');
    document.getElementById('lang-en').classList.toggle('opacity-50', lang !== 'en');

    // Végigmegyünk az összes data-i18n attribútummal rendelkező elemen
    document.querySelectorAll('[data-i18n]').forEach(element => {
        const key = element.getAttribute('data-i18n');
        if (translations[lang][key]) {
            // Ha ez egy input placeholder, azt cseréljük
            if (element.tagName === 'INPUT' && element.hasAttribute('placeholder')) {
                // Ide most nem tettünk placeholdereket, de a jövőre nézve jó ha tudja
            } else {
                element.textContent = translations[lang][key];
            }
        }
    });

    // Ha van külön JS-ből renderelt tartalom, azt is frissítjük
    if (typeof fetchClubs === 'function') fetchClubs();
    if (typeof fetchTeams === 'function') fetchTeams();
    if (typeof fetchMatches === 'function') fetchMatches();
}

// Fordító segédfüggvény a JS fájlokhoz (dinamikus HTML stringekhez)
function t(key) {
    return translations[currentLang][key] || key;
}

// Betöltéskor alkalmazzuk a mentett nyelvet
document.addEventListener('DOMContentLoaded', () => {
    setLanguage(currentLang);
});