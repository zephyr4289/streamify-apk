// Streamify Web Player App Logic (Multi-User Profile Support)

const audioEngine = document.getElementById('audio-engine');
let currentTrack = null;
let recentHistory = [];
let upNextQueue = [];
let allTracks = [];
let isPlaying = false;
let authToken = localStorage.getItem('streamify_token') || null;
let currentUser = null;
let likedTrackIds = new Set();

const cardArtAssets = [
    'assets/card1img.jpeg',
    'assets/card2img.jpeg',
    'assets/card3img.jpeg',
    'assets/card4img.jpeg',
    'assets/card5img.jpeg',
    'assets/card6img.jpeg',
    'assets/card7img.jpg',
    'assets/card8img.jpg',
    'assets/card9img.png',
    'assets/card10img.png'
];

function getTrackArt(trackId) {
    const idx = (trackId - 1) % cardArtAssets.length;
    return cardArtAssets[idx >= 0 ? idx : 0];
}

function getAuthHeaders() {
    const headers = { 'Content-Type': 'application/json' };
    if (authToken) {
        headers['Authorization'] = `Bearer ${authToken}`;
    }
    return headers;
}

// Initialize Web Player on Load
document.addEventListener('DOMContentLoaded', () => {
    updateGreeting();
    setupAudioListeners();
    checkAuthSession();
});

function updateGreeting() {
    const hour = new Date().getHours();
    const greetingEl = document.getElementById('greeting-text');
    if (!greetingEl) return;
    const prefix = currentUser ? `, ${escapeHtml(currentUser.username)}` : '';
    if (hour < 12) greetingEl.textContent = `Good morning${prefix}`;
    else if (hour < 18) greetingEl.textContent = `Good afternoon${prefix}`;
    else greetingEl.textContent = `Good evening${prefix}`;
}

// Check Session & Profile Auth
async function checkAuthSession() {
    if (!authToken) {
        showAuthModal();
        return;
    }
    try {
        const resp = await fetch('/api/v1/auth/me', { headers: getAuthHeaders() });
        if (resp.ok) {
            const data = await resp.json();
            currentUser = data.user;
            updateUserProfileUI();
            hideAuthModal();
            loadTracks();
            loadLikedTracks();
        } else {
            showAuthModal();
        }
    } catch (e) {
        console.log('[Auth] Session check fallback:', e);
        // Fallback for offline UI preview
        currentUser = { id: 1, username: 'Profile' };
        updateUserProfileUI();
        hideAuthModal();
        loadTracks();
    }
}

function showAuthModal() {
    const modal = document.getElementById('auth-modal');
    if (modal) modal.style.display = 'flex';
}

function hideAuthModal() {
    const modal = document.getElementById('auth-modal');
    if (modal) modal.style.display = 'none';
}

async function handleAuthSubmit(e) {
    e.preventDefault();
    const username = document.getElementById('auth-username').value.trim();
    const pin = document.getElementById('auth-pin').value.trim();
    const errEl = document.getElementById('auth-error-msg');

    if (!username || pin.length < 4) {
        errEl.textContent = 'Please enter a valid profile name and 4-digit PIN.';
        errEl.style.display = 'block';
        return;
    }

    try {
        const resp = await fetch('/api/v1/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username, pin })
        });
        const data = await resp.json();
        if (resp.ok && data.token) {
            authToken = data.token;
            localStorage.setItem('streamify_token', authToken);
            currentUser = data.user;
            updateUserProfileUI();
            hideAuthModal();
            errEl.style.display = 'none';
            loadTracks();
            loadLikedTracks();
        } else {
            errEl.textContent = data.error || 'Authentication failed.';
            errEl.style.display = 'block';
        }
    } catch (err) {
        errEl.textContent = 'Failed to connect to Streamify server.';
        errEl.style.display = 'block';
    }
}

function updateUserProfileUI() {
    if (!currentUser) return;
    const nameEl = document.getElementById('user-display-name');
    if (nameEl) nameEl.textContent = currentUser.username;
    updateGreeting();
}

async function logoutUser() {
    if (authToken) {
        try {
            await fetch('/api/v1/auth/logout', { method: 'POST', headers: getAuthHeaders() });
        } catch (e) {}
    }
    authToken = null;
    currentUser = null;
    likedTrackIds.clear();
    localStorage.removeItem('streamify_token');
    showAuthModal();
}

// Fetch Tracks Catalog from C++ Backend API
async function loadTracks() {
    try {
        const resp = await fetch('/api/v1/tracks', { headers: getAuthHeaders() });
        if (resp.ok) {
            allTracks = await resp.json();
            renderAllTracks(allTracks);
            if (allTracks.length > 0) {
                renderQuickGrid(allTracks.slice(0, 6));
                fetchRecommendations(allTracks[0].id);
            }
        }
    } catch (e) {
        console.log('[Streamify Web] Using fallback track data:', e);
        allTracks = [
            { id: 1, title: 'Midnight City Ambient', artist: 'Cyberwave', album: 'Nocturnal', bpm: 120.0, key: 'C' },
            { id: 2, title: 'Neon Horizon', artist: 'Synthetica', album: 'Synthwave Hits', bpm: 128.0, key: 'G' },
            { id: 3, title: 'Sunset Acoustic Drift', artist: 'Acoustica', album: 'Unplugged', bpm: 105.0, key: 'D' },
            { id: 4, title: 'Lofi Rain & Coffee', artist: 'Chill Hop Beats', album: 'Study Sessions', bpm: 90.0, key: 'F' }
        ];
        renderAllTracks(allTracks);
        renderQuickGrid(allTracks);
    }
}

async function loadLikedTracks() {
    try {
        const resp = await fetch('/api/v1/user/liked', { headers: getAuthHeaders() });
        if (resp.ok) {
            const tracks = await resp.json();
            likedTrackIds = new Set(tracks.map(t => t.id));
            updateLikeButtonState();
            renderLikedTracksSection(tracks);
        }
    } catch (e) {}
}

async function toggleLikeCurrentTrack() {
    if (!currentTrack) return;
    await toggleLikeTrack(currentTrack.id);
}

async function toggleLikeTrack(trackId) {
    try {
        const resp = await fetch('/api/v1/user/liked/toggle', {
            method: 'POST',
            headers: getAuthHeaders(),
            body: JSON.stringify({ track_id: trackId })
        });
        if (resp.ok) {
            const res = await resp.json();
            if (res.is_liked) likedTrackIds.add(trackId);
            else likedTrackIds.delete(trackId);
            
            updateLikeButtonState();
            loadLikedTracks();
        }
    } catch (e) {}
}

function updateLikeButtonState() {
    const likeBtn = document.getElementById('like-btn');
    if (!likeBtn || !currentTrack) return;
    const isLiked = likedTrackIds.has(currentTrack.id);
    likeBtn.innerHTML = isLiked 
        ? '<i class="fa-solid fa-heart" style="color: #1DB954;"></i>' 
        : '<i class="fa-regular fa-heart"></i>';
}

function renderLikedTracksSection(likedTracks) {
    const libGrid = document.getElementById('library-grid');
    if (!libGrid) return;
    libGrid.innerHTML = '';
    if (likedTracks.length === 0) {
        libGrid.innerHTML = '<p class="empty-msg">No liked songs yet. Click the heart icon on any track to save it to your profile!</p>';
        return;
    }
    likedTracks.forEach(t => libGrid.appendChild(createMusicCard(t)));
}

function refreshTracks() {
    loadTracks();
}

function renderQuickGrid(tracks) {
    const grid = document.getElementById('quick-grid');
    if (!grid) return;
    grid.innerHTML = '';
    tracks.forEach(track => {
        const card = document.createElement('div');
        card.className = 'playlist-card-mini';
        card.innerHTML = `
            <img src="${getTrackArt(track.id)}" class="mini-art">
            <div class="mini-info">
                <div class="title">${escapeHtml(track.title)}</div>
                <div class="subtitle">${escapeHtml(track.artist)}</div>
            </div>
        `;
        card.onclick = () => playTrack(track);
        grid.appendChild(card);
    });
}

function renderAllTracks(tracks) {
    const grid = document.getElementById('all-tracks-grid');
    if (!grid) return;
    grid.innerHTML = '';
    tracks.forEach(track => {
        const card = createMusicCard(track);
        grid.appendChild(card);
    });
}

function createMusicCard(track) {
    const card = document.createElement('div');
    card.className = 'music-card';
    const isLiked = likedTrackIds.has(track.id);
    card.innerHTML = `
        <div class="card-img-wrapper">
            <img src="${getTrackArt(track.id)}" class="card-img">
            <button class="card-play-btn" onclick="event.stopPropagation(); playTrackById(${track.id})">
                <i class="fa-solid fa-play"></i>
            </button>
        </div>
        <div class="card-title">${escapeHtml(track.title)}</div>
        <div class="card-subtitle">${escapeHtml(track.artist)}</div>
    `;
    card.onclick = () => playTrack(track);
    return card;
}

// Fetch AI Recommendations from ProcEngine
async function fetchRecommendations(currentTrackId) {
    try {
        const historyParam = recentHistory.join(',');
        const userIdParam = currentUser ? currentUser.id : 1;
        const url = `/api/v1/recommend/next?current_track_id=${currentTrackId}&recent_history=${historyParam}&user_id=${userIdParam}&limit=5`;
        const resp = await fetch(url, { headers: getAuthHeaders() });
        if (resp.ok) {
            const recommendations = await resp.json();
            renderRecommendations(recommendations);
            upNextQueue = recommendations;
            renderQueue();
        }
    } catch (e) {
        console.log('[ProcEngine] AI recommendation fetch failed:', e);
    }
}

function renderRecommendations(recommendations) {
    const grid = document.getElementById('ai-recommendations-grid');
    if (!grid) return;
    grid.innerHTML = '';
    recommendations.forEach(rec => {
        const card = createMusicCard(rec);
        grid.appendChild(card);
    });
}

function renderQueue() {
    const container = document.getElementById('queue-items-list');
    if (!container) return;
    container.innerHTML = '';
    upNextQueue.forEach(item => {
        const el = document.createElement('div');
        el.className = 'track-item-mini';
        el.innerHTML = `
            <img src="${getTrackArt(item.id)}" class="mini-art">
            <div class="details">
                <div class="title">${escapeHtml(item.title)}</div>
                <div class="artist">${escapeHtml(item.artist)}</div>
            </div>
        `;
        el.onclick = () => playTrack(item);
        container.appendChild(el);
    });
}

// Playback Engine Integration
function playTrackById(trackId) {
    const track = allTracks.find(t => t.id === trackId);
    if (track) playTrack(track);
}

function playTrack(track) {
    if (currentTrack && currentTrack.id !== track.id) {
        logPlayEvent(track.id, currentTrack.id);
        recentHistory.unshift(currentTrack.id);
        if (recentHistory.length > 5) recentHistory.pop();
    }
    
    currentTrack = track;
    updatePlayerUI(track);
    updateLikeButtonState();
    
    audioEngine.src = `/api/v1/stream?id=${track.id}`;
    audioEngine.play().then(() => {
        isPlaying = true;
        updatePlayPauseIcon();
    }).catch(err => {
        console.log('[AudioEngine] Playback error or user gesture required:', err);
    });

    fetchRecommendations(track.id);
}

function togglePlay() {
    if (!currentTrack) {
        if (allTracks.length > 0) playTrack(allTracks[0]);
        return;
    }
    if (isPlaying) {
        audioEngine.pause();
        isPlaying = false;
    } else {
        audioEngine.play();
        isPlaying = true;
    }
    updatePlayPauseIcon();
}

function updatePlayPauseIcon() {
    const playImg = document.getElementById('play-icon');
    if (!playImg) return;
    playImg.src = isPlaying ? 'assets/player_icon3.png' : 'assets/play_musicbar.png';
}

function updatePlayerUI(track) {
    document.getElementById('player-title').textContent = track.title;
    document.getElementById('player-artist').textContent = track.artist;
    document.getElementById('player-cover-img').src = getTrackArt(track.id);
    document.getElementById('now-playing-mini').innerHTML = `
        <img src="${getTrackArt(track.id)}" class="mini-art">
        <div class="details">
            <div class="title">${escapeHtml(track.title)}</div>
            <div class="artist">${escapeHtml(track.artist)}</div>
        </div>
    `;
    const likeBtn = document.getElementById('like-btn');
    if (likeBtn) likeBtn.onclick = () => toggleLikeCurrentTrack();
}

function nextTrack() {
    if (currentTrack && upNextQueue.length > 0) {
        logSkipEvent(upNextQueue[0].id, currentTrack.id);
        const next = upNextQueue.shift();
        playTrack(next);
    } else if (allTracks.length > 0) {
        const nextIndex = Math.floor(Math.random() * allTracks.length);
        playTrack(allTracks[nextIndex]);
    }
}

function prevTrack() {
    if (audioEngine.currentTime > 3) {
        audioEngine.currentTime = 0;
    } else if (recentHistory.length > 0) {
        const prevId = recentHistory.shift();
        playTrackById(prevId);
    }
}

function setupAudioListeners() {
    audioEngine.addEventListener('timeupdate', () => {
        const current = audioEngine.currentTime;
        const duration = audioEngine.duration || 1;
        document.getElementById('current-time').textContent = formatTime(current);
        document.getElementById('duration-time').textContent = formatTime(duration);
        document.getElementById('seekbar').value = (current / duration) * 100;
    });

    audioEngine.addEventListener('ended', () => {
        nextTrack();
    });
}

function seekTrack(val) {
    if (audioEngine.duration) {
        audioEngine.currentTime = (val / 100) * audioEngine.duration;
    }
}

function setVolume(val) {
    audioEngine.volume = val / 100;
    const volIcon = document.getElementById('volume-icon');
    if (val == 0) volIcon.className = 'fa-solid fa-volume-xmark';
    else if (val < 50) volIcon.className = 'fa-solid fa-volume-low';
    else volIcon.className = 'fa-solid fa-volume-high';
}

function toggleMute() {
    audioEngine.muted = !audioEngine.muted;
    const volIcon = document.getElementById('volume-icon');
    volIcon.className = audioEngine.muted ? 'fa-solid fa-volume-xmark' : 'fa-solid fa-volume-high';
}

// Log Play & Skip Events to ProcEngine backend
async function logPlayEvent(currentTrackId, previousTrackId) {
    try {
        const userId = currentUser ? currentUser.id : 1;
        await fetch('/api/v1/event/play', {
            method: 'POST',
            headers: getAuthHeaders(),
            body: JSON.stringify({ current_track_id: currentTrackId, previous_track_id: previousTrackId, user_id: userId })
        });
    } catch (e) {}
}

async function logSkipEvent(currentTrackId, previousTrackId) {
    try {
        const userId = currentUser ? currentUser.id : 1;
        await fetch('/api/v1/event/skip', {
            method: 'POST',
            headers: getAuthHeaders(),
            body: JSON.stringify({ current_track_id: currentTrackId, previous_track_id: previousTrackId, user_id: userId })
        });
    } catch (e) {}
}

// UI Navigation Tabs & Search
function switchTab(tabName) {
    document.querySelectorAll('.tab-view').forEach(t => t.classList.remove('active'));
    document.querySelectorAll('.nav-item').forEach(n => n.classList.remove('active'));
    
    document.getElementById(`tab-${tabName}`).classList.add('active');
    document.getElementById(`nav-${tabName}`).classList.add('active');
    
    const searchHeader = document.getElementById('header-search-container');
    if (tabName === 'search') {
        searchHeader.style.display = 'block';
        document.getElementById('search-input').focus();
    } else {
        searchHeader.style.display = 'none';
    }
}

let currentSearchQuery = '';

function handleSearch(e) {
    const query = e.target.value.trim();
    currentSearchQuery = query;
    const lowerQuery = query.toLowerCase();
    const filtered = allTracks.filter(t => 
        t.title.toLowerCase().includes(lowerQuery) || 
        t.artist.toLowerCase().includes(lowerQuery)
    );
    const container = document.getElementById('search-results-grid');
    if (!container) return;
    container.innerHTML = '';
    
    if (filtered.length > 0) {
        filtered.forEach(track => {
            container.appendChild(createMusicCard(track));
        });
    } else if (query.length > 0) {
        container.innerHTML = `<p class="empty-msg">No local tracks found for "${escapeHtml(query)}". You can download it directly to your NAS below!</p>`;
    } else {
        container.innerHTML = `<p class="empty-msg">Type in the search bar above to query songs, artists, or genres.</p>`;
    }
}

async function triggerOnlineDownload() {
    const searchInput = document.getElementById('search-input');
    const query = searchInput ? searchInput.value.trim() : '';
    if (!query) {
        alert('Please enter a song or artist name in the search bar first!');
        if (searchInput) searchInput.focus();
        return;
    }

    const banner = document.getElementById('download-banner');
    const statusCard = document.getElementById('download-status-card');
    const statusMsg = document.getElementById('status-msg');

    if (banner) banner.style.display = 'none';
    if (statusCard) statusCard.style.display = 'flex';
    if (statusMsg) statusMsg.textContent = `Downloading "${query}" from global sources to NAS...`;

    try {
        const resp = await fetch('/api/v1/download', {
            method: 'POST',
            headers: getAuthHeaders(),
            body: JSON.stringify({ query: query })
        });
        const data = await resp.json();
        if (resp.ok && data.status === 'success' && data.track) {
            if (statusMsg) statusMsg.textContent = `Downloaded & indexed "${data.track.title}" successfully!`;
            await loadTracks();
            playTrack(data.track);
            setTimeout(() => {
                if (statusCard) statusCard.style.display = 'none';
                if (banner) banner.style.display = 'flex';
            }, 3000);
        } else {
            alert('Download error: ' + (data.error || 'Failed to acquire audio track'));
            if (statusCard) statusCard.style.display = 'none';
            if (banner) banner.style.display = 'flex';
        }
    } catch (err) {
        alert('Download error: Failed to connect to server.');
        if (statusCard) statusCard.style.display = 'none';
        if (banner) banner.style.display = 'flex';
    }
}

function toggleQueue() {
    const drawer = document.getElementById('queue-drawer');
    drawer.classList.toggle('open');
}

function formatTime(seconds) {
    if (isNaN(seconds)) return '0:00';
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins}:${secs < 10 ? '0' : ''}${secs}`;
}

function escapeHtml(str) {
    if (!str) return '';
    return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}
