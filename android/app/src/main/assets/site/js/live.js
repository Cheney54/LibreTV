(function () {
    const SOURCE_FILE = 'data/live-sources.json';
    const CUSTOM_SOURCES_KEY = 'liveCustomSources';
    const LAST_SOURCE_KEY = 'liveLastSourceId';
    const FAVORITES_KEY = 'liveFavoriteChannels';
    const CACHE_PREFIX = 'liveSourceCache:';
    const CACHE_TTL = 6 * 60 * 60 * 1000;
    const SOURCE_FETCH_TIMEOUT = 12000;
    const MAX_CHANNELS_RENDERED = 300;
    const LIVE_RETRY_LIMIT = 8;
    const LIVE_RETRY_DELAY = 15000;
    const LIVE_STALL_TIMEOUT = 35000;
    const LIVE_BUFFER_SECONDS = 180;
    const LIVE_MONITOR_INTERVAL = 5000;
    const LIVE_NO_DATA_TIMEOUT = 60000;
    const LIVE_END_CONTINUE_DELAY = 1200;
    const LIVE_STARTUP_TIMEOUT = 8000;
    const FALLBACK_SWITCH_AFTER_RETRIES = 3;
    const FALLBACK_PREPARE_DELAY = 1500;

    const state = {
        sources: [],
        channels: [],
        channelCache: {},
        filteredChannels: [],
        activeGroup: '全部',
        activeChannelUrl: '',
        activeChannel: null,
        favorites: readJson(FAVORITES_KEY, []),
        hls: null,
        loadToken: 0,
        retryCount: 0,
        retryTimer: null,
        stallTimer: null,
        monitorTimer: null,
        endTimer: null,
        startupTimer: null,
        lastDataAt: 0,
        softRecoverCount: 0,
        usingProxyPlayback: false,
        preparingFallbacks: false,
        fallbackSwitching: false,
        failedUrls: new Set()
    };

    document.addEventListener('DOMContentLoaded', initLivePage);

    async function initLivePage() {
        bindEvents();
        await loadSources();
        renderSourceSelect();
        await loadSelectedSource(false);
    }

    function bindEvents() {
        document.getElementById('sourceSelect')?.addEventListener('change', () => loadSelectedSource(false));
        document.getElementById('refreshSource')?.addEventListener('click', () => loadSelectedSource(true));
        document.getElementById('channelSearch')?.addEventListener('input', () => applyChannelFilters());
        document.getElementById('favoriteToggle')?.addEventListener('click', toggleCurrentFavorite);
        document.getElementById('saveCustomSource')?.addEventListener('click', saveCustomSource);

        const video = document.getElementById('liveVideo');
        video?.addEventListener('waiting', () => scheduleStallCheck());
        video?.addEventListener('stalled', () => scheduleStallCheck());
        video?.addEventListener('loadeddata', markPlaybackData);
        video?.addEventListener('playing', () => {
            clearStallCheck();
            clearRetryTimer();
            state.retryCount = 0;
            state.softRecoverCount = 0;
            document.getElementById('livePlayerHint')?.classList.add('hidden');
        });
        video?.addEventListener('error', () => scheduleLiveRetry('播放中断，正在重连。'));
        video?.addEventListener('ended', continueLivePlayback);
    }

    async function loadSources() {
        try {
            const response = await fetch(SOURCE_FILE, { cache: 'no-store' });
            const data = await response.json();
            const customSources = readJson(CUSTOM_SOURCES_KEY, []);
            state.sources = [...(data.sources || []), ...customSources];
            state.defaultSourceId = data.defaultSourceId || state.sources[0]?.id;
        } catch (error) {
            console.warn('加载直播源配置失败:', error);
            state.sources = readJson(CUSTOM_SOURCES_KEY, []);
            state.defaultSourceId = state.sources[0]?.id;
        }
    }

    function renderSourceSelect() {
        const select = document.getElementById('sourceSelect');
        if (!select) return;

        const lastSourceId = localStorage.getItem(LAST_SOURCE_KEY) || state.defaultSourceId;
        select.innerHTML = state.sources.map(source => {
            const selected = source.id === lastSourceId ? 'selected' : '';
            return `<option value="${escapeHtml(source.id)}" ${selected}>${escapeHtml(source.name)}</option>`;
        }).join('');

        if (!select.value && state.sources[0]) {
            select.value = state.sources[0].id;
        }
    }

    async function loadSelectedSource(forceRefresh) {
        const select = document.getElementById('sourceSelect');
        const source = state.sources.find(item => item.id === select?.value) || state.sources[0];
        const token = Date.now();
        state.loadToken = token;

        if (!source) {
            showChannelMessage('还没有频道列表，请先添加一个直播源地址。');
            return;
        }

        localStorage.setItem(LAST_SOURCE_KEY, source.id);
        updateSourceNote(source, '正在加载频道...');
        showChannelMessage('正在准备频道列表...');

        const cached = readSourceCache(source);
        if (!forceRefresh && cached?.text) {
            renderSourceText(source, cached.text);
            updateSourceNote(source, cached.fresh ? '已从缓存打开，正在检查更新。' : '先显示上次的频道，正在刷新。');
            prepareFallbackSources(source.id);
            refreshSourceInBackground(source, token);
            return;
        }

        try {
            const text = await getSourceText(source, forceRefresh);
            if (state.loadToken !== token) return;
            renderSourceText(source, text);
            updateSourceNote(source, `已加载 ${state.channels.length} 个频道。`);
            prepareFallbackSources(source.id);
        } catch (error) {
            console.warn('加载频道失败:', error);
            showChannelMessage('频道列表加载失败，可以点刷新，或换一个频道列表。');
            updateSourceNote(source, '加载失败，可能是网络或源地址不可用。');
        }
    }

    function renderSourceText(source, text) {
        state.channels = parseM3u(text).map(channel => ({
            ...channel,
            sourceId: source.id,
            sourceName: source.name
        }));
        state.channelCache[source.id] = state.channels;
        state.activeGroup = '全部';
        applyChannelFilters();
    }

    async function loadSourceChannels(source, forceNetwork = false) {
        if (!forceNetwork && state.channelCache[source.id]) {
            return state.channelCache[source.id];
        }

        const text = await getSourceText(source, forceNetwork);
        const channels = parseM3u(text).map(channel => ({
            ...channel,
            sourceId: source.id,
            sourceName: source.name
        }));
        state.channelCache[source.id] = channels;
        return channels;
    }

    async function refreshSourceInBackground(source, token) {
        try {
            const text = await getSourceText(source, true);
            if (state.loadToken !== token) return;
            renderSourceText(source, text);
            updateSourceNote(source, `已更新 ${state.channels.length} 个频道。`);
        } catch (error) {
            console.warn('后台刷新频道失败:', error);
        }
    }

    async function prepareFallbackSources(currentSourceId) {
        if (state.preparingFallbacks) return;
        state.preparingFallbacks = true;

        setTimeout(async () => {
            try {
                for (const source of state.sources) {
                    if (source.id === currentSourceId || state.channelCache[source.id]) continue;
                    try {
                        await loadSourceChannels(source, false);
                    } catch (error) {
                        console.warn(`准备备用频道失败: ${source.name}`, error);
                    }
                }
            } finally {
                state.preparingFallbacks = false;
            }
        }, FALLBACK_PREPARE_DELAY);
    }

    function readSourceCache(source) {
        const cacheKey = CACHE_PREFIX + source.id;
        const cached = readJson(cacheKey, null);
        if (!cached?.text) return null;
        return {
            text: cached.text,
            fresh: Date.now() - cached.time < CACHE_TTL
        };
    }

    async function getSourceText(source, forceNetwork = false) {
        const cacheKey = CACHE_PREFIX + source.id;
        const cached = readSourceCache(source);
        if (!forceNetwork && cached?.fresh) {
            return cached.text;
        }

        const url = source.url;
        const requestUrl = typeof PROXY_URL !== 'undefined' ? PROXY_URL + encodeURIComponent(url) : url;
        const response = await fetchWithTimeout(requestUrl, { cache: 'no-store' }, SOURCE_FETCH_TIMEOUT);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);

        const text = await response.text();
        if (!text.includes('#EXTM3U') && !text.includes('#EXTINF')) {
            throw new Error('频道列表格式不正确');
        }

        localStorage.setItem(cacheKey, JSON.stringify({ time: Date.now(), text }));
        return text;
    }

    function fetchWithTimeout(url, options, timeout) {
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), timeout);
        return fetch(url, {
            ...options,
            signal: controller.signal
        }).finally(() => clearTimeout(timer));
    }

    function parseM3u(text) {
        const channels = [];
        const lines = text.replace(/\r/g, '').split('\n');
        let pendingInfo = null;

        for (const rawLine of lines) {
            const line = rawLine.trim();
            if (!line) continue;

            if (line.startsWith('#EXTINF')) {
                pendingInfo = parseExtinf(line);
                continue;
            }

            if (line.startsWith('#')) continue;
            if (!/^https?:\/\//i.test(line) && !line.startsWith('/proxy/')) continue;

            const name = pendingInfo?.name || getNameFromUrl(line);
            channels.push({
                id: stableId(name + line),
                name,
                group: normalizeGroup(pendingInfo?.group || '未分组'),
                logo: pendingInfo?.logo || '',
                url: line
            });
            pendingInfo = null;
        }

        return dedupeChannels(channels);
    }

    function parseExtinf(line) {
        const attrText = line.split(',', 1)[0];
        const name = line.includes(',') ? line.slice(line.indexOf(',') + 1).trim() : '未命名频道';
        return {
            name: getAttr(attrText, 'tvg-name') || name || '未命名频道',
            group: getAttr(attrText, 'group-title') || '未分组',
            logo: getAttr(attrText, 'tvg-logo') || ''
        };
    }

    function getAttr(text, attrName) {
        const match = text.match(new RegExp(`${attrName}="([^"]*)"`, 'i'));
        return match ? match[1].trim() : '';
    }

    function dedupeChannels(channels) {
        const seen = new Set();
        return channels.filter(channel => {
            const key = channel.name + '|' + channel.url;
            if (seen.has(key)) return false;
            seen.add(key);
            return true;
        });
    }

    function normalizeGroup(group) {
        if (!group || group === 'undefined') return '未分组';
        if (/央视|CCTV/i.test(group)) return '央视';
        if (/卫视/.test(group)) return '卫视';
        if (/体育|sport/i.test(group)) return '体育';
        if (/少儿|kids|cartoon|动画/i.test(group)) return '少儿';
        if (/新闻|news/i.test(group)) return '新闻';
        return group;
    }

    function applyChannelFilters() {
        const keyword = (document.getElementById('channelSearch')?.value || '').trim().toLowerCase();
        state.filteredChannels = state.channels.filter(channel => {
            const isFavoriteGroup = state.activeGroup === '收藏';
            const matchesGroup = state.activeGroup === '全部'
                || (isFavoriteGroup && isFavorite(channel))
                || channel.group === state.activeGroup;
            const matchesKeyword = !keyword
                || channel.name.toLowerCase().includes(keyword)
                || channel.group.toLowerCase().includes(keyword);
            return matchesGroup && matchesKeyword;
        });

        renderGroupTabs();
        renderChannelList();
    }

    function renderGroupTabs() {
        const container = document.getElementById('groupTabs');
        if (!container) return;

        const groups = ['全部'];
        if (state.favorites.length) groups.push('收藏');
        for (const channel of state.channels) {
            if (!groups.includes(channel.group)) groups.push(channel.group);
        }

        container.innerHTML = groups.map(group => {
            const active = group === state.activeGroup ? 'active' : '';
            return `<button class="live-tab ${active}" type="button" data-group="${escapeHtml(group)}">${escapeHtml(group)}</button>`;
        }).join('');

        container.querySelectorAll('.live-tab').forEach(button => {
            button.addEventListener('click', () => {
                state.activeGroup = button.dataset.group || '全部';
                applyChannelFilters();
            });
        });
    }

    function renderChannelList() {
        const list = document.getElementById('channelList');
        const stats = document.getElementById('channelStats');
        if (!list || !stats) return;

        const channels = state.filteredChannels.slice(0, MAX_CHANNELS_RENDERED);
        const limitedText = state.filteredChannels.length > channels.length
            ? `，已先显示前 ${channels.length} 个`
            : '';
        stats.textContent = `找到 ${state.filteredChannels.length} 个频道${limitedText}`;

        if (!channels.length) {
            list.innerHTML = '<div class="live-empty">没有找到频道。可以换个分组，或者换一个频道列表。</div>';
            return;
        }

        list.innerHTML = channels.map(channel => {
            const active = channel.url === state.activeChannelUrl ? 'active' : '';
            const logo = channel.logo
                ? `<img class="live-channel-logo" src="${escapeAttr(channel.logo)}" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer" onerror="this.replaceWith(Object.assign(document.createElement('span'),{className:'live-channel-logo fallback',textContent:'${escapeAttr(channel.name.slice(0, 1))}'}))">`
                : `<span class="live-channel-logo fallback">${escapeHtml(channel.name.slice(0, 1))}</span>`;
            const star = isFavorite(channel) ? '★' : '';
            return `
                <button class="live-channel ${active}" type="button" data-url="${escapeAttr(channel.url)}">
                    ${logo}
                    <span class="live-channel-main">
                        <span class="live-channel-name">${escapeHtml(channel.name)}</span>
                        <span class="live-channel-group">${escapeHtml(channel.group)}</span>
                    </span>
                    <span class="live-channel-star">${star}</span>
                </button>
            `;
        }).join('');

        list.querySelectorAll('.live-channel').forEach(button => {
            button.addEventListener('click', () => {
                const channel = state.channels.find(item => item.url === button.dataset.url);
                if (channel) playChannel(channel);
            });
        });
    }

    function playChannel(channel) {
        state.failedUrls.clear();
        state.fallbackSwitching = false;
        startChannel(channel, false, { forceProxy: false });
    }

    function startChannel(channel, isRetry, options = {}) {
        const video = document.getElementById('liveVideo');
        if (!video) return;

        clearRetryTimer();
        clearStallCheck();
        clearEndTimer();
        clearStartupTimer();
        if (!isRetry) {
            state.retryCount = 0;
        }
        state.lastDataAt = Date.now();

        state.activeChannel = channel;
        state.activeChannelUrl = channel.url;
        const playUrl = getWebPlaybackUrl(channel.url, options.forceProxy);
        state.usingProxyPlayback = playUrl.startsWith('/proxy/');

        document.getElementById('currentChannelName').textContent = channel.name;
        document.getElementById('currentChannelMeta').textContent = `${channel.group} · ${channel.sourceName}`;

        if (window.AndroidLivePlayer?.play) {
            try {
                window.AndroidLivePlayer.play(channel.name, getNativePlaybackUrl(channel.url));
                document.getElementById('livePlayerHint')?.classList.add('hidden');
                updateFavoriteButton(channel);
                updateActiveChannelInList();
                return;
            } catch (error) {
                console.warn('原生播放器启动失败，回退网页播放:', error);
            }
        }

        if (state.hls) {
            state.hls.destroy();
            state.hls = null;
        }
        stopPlaybackMonitor();

        video.pause();
        video.removeAttribute('src');
        video.load();

        if (window.Hls && Hls.isSupported()) {
            state.hls = new Hls({
                lowLatencyMode: false,
                enableWorker: true,
                startFragPrefetch: true,
                liveDurationInfinity: true,
                backBufferLength: 180,
                maxBufferLength: LIVE_BUFFER_SECONDS,
                maxMaxBufferLength: 300,
                maxBufferHole: 0.5,
                liveSyncDurationCount: 8,
                liveMaxLatencyDurationCount: 30,
                initialLiveManifestSize: 1,
                testBandwidth: false,
                abrEwmaDefaultEstimate: 5000000,
                manifestLoadingTimeOut: 20000,
                levelLoadingTimeOut: 20000,
                fragLoadingTimeOut: 60000,
                manifestLoadingMaxRetry: 4,
                levelLoadingMaxRetry: 4,
                fragLoadingMaxRetry: 10,
                fragLoadingRetryDelay: 1200,
                fragLoadingMaxRetryTimeout: 15000,
                nudgeMaxRetry: 10
            });
            state.hls.loadSource(playUrl);
            state.hls.attachMedia(video);
            scheduleStartupCheck(channel);
            state.hls.on(Hls.Events.MANIFEST_PARSED, () => {
                markPlaybackData();
                startPlaybackMonitor();
                video.play().catch(() => {});
            });
            state.hls.on(Hls.Events.LEVEL_LOADED, markPlaybackData);
            state.hls.on(Hls.Events.FRAG_LOADED, markPlaybackData);
            state.hls.on(Hls.Events.ERROR, (_, data) => {
                if (!data?.fatal && data?.details === Hls.ErrorDetails.BUFFER_STALLED_ERROR) {
                    scheduleStallCheck();
                    return;
                }

                if (!data?.fatal) return;

                if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
                    if (switchToProxyPlayback(channel)) return;
                    recoverLiveStream('网络不稳定，正在继续加载。', { quiet: true });
                    return;
                }

                if (data.type === Hls.ErrorTypes.MEDIA_ERROR && state.hls) {
                    try {
                        state.hls.recoverMediaError();
                    } catch {
                        scheduleLiveRetry('播放中断，正在重连。');
                    }
                    return;
                }

                scheduleLiveRetry('这个频道断开了，正在重连。');
            });
        } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
            video.src = playUrl;
            startPlaybackMonitor();
            scheduleStartupCheck(channel);
            video.play().catch(() => {});
        } else {
            video.src = playUrl;
            startPlaybackMonitor();
            scheduleStartupCheck(channel);
            video.play().catch(() => {});
        }

        if (isRetry) {
            showPlayerHint('正在重连频道', `${channel.name} 第 ${state.retryCount} 次重试`);
        } else {
            document.getElementById('livePlayerHint')?.classList.add('hidden');
        }
        updateFavoriteButton(channel);
        updateActiveChannelInList();
    }

    function updateActiveChannelInList() {
        const list = document.getElementById('channelList');
        if (!list) return;

        list.querySelectorAll('.live-channel').forEach(button => {
            const active = button.dataset.url === state.activeChannelUrl;
            button.classList.toggle('active', active);
            if (active) {
                button.setAttribute('aria-current', 'true');
            } else {
                button.removeAttribute('aria-current');
            }
        });
    }

    function getNativePlaybackUrl(url) {
        if (url.startsWith('/proxy/')) {
            try {
                return decodeURIComponent(url.slice('/proxy/'.length));
            } catch {
                return url;
            }
        }
        return url;
    }

    function getWebPlaybackUrl(url, forceProxy) {
        if (url.startsWith('/proxy/')) return url;
        if (forceProxy && canUseProxy(url)) {
            return PROXY_URL + encodeURIComponent(url);
        }
        return url;
    }

    function canUseProxy(url) {
        return typeof PROXY_URL !== 'undefined' && /^https?:\/\//i.test(url);
    }

    function switchToProxyPlayback(channel) {
        if (state.usingProxyPlayback || !canUseProxy(channel.url)) {
            return false;
        }

        showPlayerHint('直连不稳定', '正在切换备用播放通道。');
        setTimeout(() => startChannel(channel, true, { forceProxy: true }), 300);
        return true;
    }

    async function handleSlowStartup(channel) {
        const video = document.getElementById('liveVideo');
        if (!video || !channel || state.activeChannelUrl !== channel.url) return;

        const hasData = video.readyState >= 2 || getBufferedAhead(video) > 0;
        if (hasData) return;

        if (switchToProxyPlayback(channel)) return;

        state.retryCount = Math.max(state.retryCount, FALLBACK_SWITCH_AFTER_RETRIES);
        if (await switchToFallbackChannel('当前线路加载较慢')) return;

        recoverLiveStream('当前线路加载较慢，正在继续等待。', { quiet: true });
    }

    async function scheduleLiveRetry(message) {
        const channel = state.activeChannel;
        if (!channel || state.retryTimer) return;

        state.retryCount += 1;
        if (state.retryCount >= FALLBACK_SWITCH_AFTER_RETRIES && await switchToFallbackChannel(message)) {
            return;
        }

        if (state.retryCount > LIVE_RETRY_LIMIT) {
            showPlayerHint('这个频道暂时不稳定', '已经重试多次，可以换一个频道试试。');
            return;
        }

        showPlayerHint(message, `${channel.name} 将自动重试 ${state.retryCount}/${LIVE_RETRY_LIMIT}`);
        state.retryTimer = setTimeout(() => {
            state.retryTimer = null;
            startChannel(channel, true, { forceProxy: state.usingProxyPlayback });
        }, LIVE_RETRY_DELAY);
    }

    async function recoverLiveStream(message, options = {}) {
        const channel = state.activeChannel;
        if (!channel || state.retryTimer) return;

        if (options.quiet) {
            state.softRecoverCount += 1;
            try {
                state.hls?.startLoad(-1);
            } catch {
                // 后台恢复失败时，继续走下方的正式恢复。
            }

            if (state.softRecoverCount < 4) {
                return;
            }
        }

        state.retryCount += 1;
        if (state.retryCount >= FALLBACK_SWITCH_AFTER_RETRIES && await switchToFallbackChannel(message)) {
            return;
        }

        if (state.retryCount > LIVE_RETRY_LIMIT) {
            showPlayerHint('这个频道暂时不稳定', '已经尝试恢复多次，可以换一个频道试试。');
            return;
        }

        showPlayerHint(message, `${channel.name} 正在尝试恢复 ${state.retryCount}/${LIVE_RETRY_LIMIT}`);
        try {
            state.hls?.startLoad(-1);
        } catch {
            // startLoad 失败时交给下面的兜底重载。
        }

        state.retryTimer = setTimeout(() => {
            state.retryTimer = null;
            startChannel(channel, true, { forceProxy: state.usingProxyPlayback });
        }, LIVE_RETRY_DELAY);
    }

    async function switchToFallbackChannel(reason) {
        const current = state.activeChannel;
        if (!current || state.fallbackSwitching) return false;

        state.fallbackSwitching = true;
        state.failedUrls.add(current.url);
        showPlayerHint(reason, '正在寻找备用频道...');

        try {
            const fallback = await findFallbackChannel(current);
            if (!fallback) return false;

            clearRetryTimer();
            state.retryCount = 0;
            showPlayerHint('已找到备用频道', `正在切换到 ${fallback.sourceName} 的 ${fallback.name}`);
            setTimeout(() => startChannel(fallback, false, { forceProxy: false }), 500);
            return true;
        } finally {
            state.fallbackSwitching = false;
        }
    }

    async function findFallbackChannel(current) {
        const currentKey = normalizeChannelName(current.name);
        const currentCctvKey = getCctvKey(current.name);
        let bestMatch = null;
        let bestScore = 0;

        for (const source of state.sources) {
            let channels = state.channelCache[source.id];
            if (!channels) {
                try {
                    channels = await loadSourceChannels(source, false);
                } catch {
                    continue;
                }
            }

            for (const channel of channels) {
                if (channel.url === current.url || state.failedUrls.has(channel.url)) continue;
                const score = getFallbackScore(channel, currentKey, currentCctvKey, source.id === current.sourceId);
                if (score > bestScore) {
                    bestScore = score;
                    bestMatch = channel;
                }
            }

            if (bestScore >= 100) break;
        }

        return bestScore >= 80 ? bestMatch : null;
    }

    function getFallbackScore(channel, currentKey, currentCctvKey, sameSource) {
        const channelKey = normalizeChannelName(channel.name);
        const channelCctvKey = getCctvKey(channel.name);
        let score = 0;

        if (channelKey && channelKey === currentKey) score = 100;
        else if (channelCctvKey && channelCctvKey === currentCctvKey) score = 95;
        else if (currentKey.length >= 4 && channelKey.includes(currentKey)) score = 82;
        else if (currentKey.length >= 4 && currentKey.includes(channelKey)) score = 80;

        if (!score) return 0;
        if (!sameSource) score += 5;
        if (/ipv6|v6/i.test(channel.sourceId || '')) score -= 3;
        return score;
    }

    function normalizeChannelName(name) {
        return String(name || '')
            .toLowerCase()
            .replace(/cctv[\s-]*(\d+)/gi, 'cctv$1')
            .replace(/中央电视台/g, 'cctv')
            .replace(/频道|高清|超清|标清|蓝光|hd|fhd|uhd|4k|1080p|720p|576p|480p/gi, '')
            .replace(/[^\w\u4e00-\u9fa5]/g, '');
    }

    function getCctvKey(name) {
        const normalized = normalizeChannelName(name);
        const match = normalized.match(/cctv(\d+)/i);
        return match ? `cctv${match[1]}` : '';
    }

    function scheduleStallCheck() {
        clearStallCheck();
        state.stallTimer = setTimeout(() => {
            const video = document.getElementById('liveVideo');
            if (!video || video.paused || video.ended) return;
            recoverLiveStream('直播卡住了，正在继续加载。', { quiet: true });
        }, LIVE_STALL_TIMEOUT);
    }

    function continueLivePlayback() {
        const video = document.getElementById('liveVideo');
        if (!video || !state.activeChannel || state.endTimer) return;

        try {
            state.hls?.startLoad(-1);
        } catch {
            // 后面还有一次兜底恢复。
        }

        state.endTimer = setTimeout(() => {
            state.endTimer = null;
            const latestVideo = document.getElementById('liveVideo');
            if (!latestVideo || !state.activeChannel) return;

            if (latestVideo.ended || latestVideo.readyState < 2) {
                recoverLiveStream('直播正在继续加载。', { quiet: true });
                return;
            }

            latestVideo.play().catch(() => {});
        }, LIVE_END_CONTINUE_DELAY);
    }

    function startPlaybackMonitor() {
        stopPlaybackMonitor();
        state.monitorTimer = setInterval(() => {
            const video = document.getElementById('liveVideo');
            if (!video || video.paused || video.ended) return;

            const bufferedAhead = getBufferedAhead(video);
            const noDataFor = Date.now() - state.lastDataAt;
            if (video.readyState < 3 && bufferedAhead < 1) {
                if (noDataFor > LIVE_STALL_TIMEOUT) {
                    recoverLiveStream('直播缓冲不足，正在继续加载。', { quiet: true });
                }
                return;
            }

            if (bufferedAhead < 2 && noDataFor > LIVE_NO_DATA_TIMEOUT) {
                recoverLiveStream('直播数据中断，正在继续加载。');
            }
        }, LIVE_MONITOR_INTERVAL);
    }

    function markPlaybackData() {
        state.lastDataAt = Date.now();
        state.softRecoverCount = 0;
        clearStartupTimer();
        clearRetryTimer();
        document.getElementById('livePlayerHint')?.classList.add('hidden');
    }

    function stopPlaybackMonitor() {
        if (state.monitorTimer) {
            clearInterval(state.monitorTimer);
            state.monitorTimer = null;
        }
    }

    function getBufferedAhead(video) {
        if (!video.buffered) return 0;
        for (let i = 0; i < video.buffered.length; i += 1) {
            if (video.currentTime >= video.buffered.start(i) && video.currentTime <= video.buffered.end(i)) {
                return video.buffered.end(i) - video.currentTime;
            }
        }
        return 0;
    }

    function clearRetryTimer() {
        if (state.retryTimer) {
            clearTimeout(state.retryTimer);
            state.retryTimer = null;
        }
    }

    function clearStallCheck() {
        if (state.stallTimer) {
            clearTimeout(state.stallTimer);
            state.stallTimer = null;
        }
    }

    function clearEndTimer() {
        if (state.endTimer) {
            clearTimeout(state.endTimer);
            state.endTimer = null;
        }
    }

    function scheduleStartupCheck(channel) {
        clearStartupTimer();
        state.startupTimer = setTimeout(() => {
            state.startupTimer = null;
            handleSlowStartup(channel);
        }, LIVE_STARTUP_TIMEOUT);
    }

    function clearStartupTimer() {
        if (state.startupTimer) {
            clearTimeout(state.startupTimer);
            state.startupTimer = null;
        }
    }

    function showPlayerHint(title, subtitle) {
        const hint = document.getElementById('livePlayerHint');
        if (!hint) return;
        hint.querySelector('.live-player-title').textContent = title;
        hint.querySelector('.live-player-subtitle').textContent = subtitle;
        hint.classList.remove('hidden');
    }

    function showChannelMessage(message) {
        const list = document.getElementById('channelList');
        const stats = document.getElementById('channelStats');
        if (stats) stats.textContent = '';
        if (list) list.innerHTML = `<div class="live-empty">${escapeHtml(message)}</div>`;
    }

    function updateSourceNote(source, status) {
        const note = document.getElementById('sourceNote');
        if (!note) return;
        note.textContent = `${status} ${source.note || ''}`;
    }

    function toggleCurrentFavorite() {
        const channel = state.channels.find(item => item.url === state.activeChannelUrl);
        if (!channel) return;

        const key = favoriteKey(channel);
        if (state.favorites.includes(key)) {
            state.favorites = state.favorites.filter(item => item !== key);
        } else {
            state.favorites.push(key);
        }

        localStorage.setItem(FAVORITES_KEY, JSON.stringify(state.favorites));
        updateFavoriteButton(channel);
        applyChannelFilters();
    }

    function updateFavoriteButton(channel) {
        const button = document.getElementById('favoriteToggle');
        if (!button) return;
        const active = isFavorite(channel);
        button.textContent = active ? '★' : '☆';
        button.classList.toggle('active', active);
    }

    function isFavorite(channel) {
        return state.favorites.includes(favoriteKey(channel));
    }

    function favoriteKey(channel) {
        return `${channel.name}|${channel.url}`;
    }

    function saveCustomSource() {
        const nameInput = document.getElementById('customSourceName');
        const urlInput = document.getElementById('customSourceUrl');
        const name = nameInput?.value.trim();
        const url = urlInput?.value.trim();

        if (!name || !/^https?:\/\//i.test(url || '')) {
            alert('请填写名称和正确的频道列表地址。');
            return;
        }

        const customSources = readJson(CUSTOM_SOURCES_KEY, []);
        const source = {
            id: 'custom-' + Date.now(),
            name,
            url,
            homepage: '',
            note: '你自己添加的频道列表。'
        };
        customSources.push(source);
        localStorage.setItem(CUSTOM_SOURCES_KEY, JSON.stringify(customSources));
        state.sources.push(source);
        localStorage.setItem(LAST_SOURCE_KEY, source.id);
        nameInput.value = '';
        urlInput.value = '';
        renderSourceSelect();
        loadSelectedSource(false);
    }

    function readJson(key, fallback) {
        try {
            return JSON.parse(localStorage.getItem(key) || JSON.stringify(fallback));
        } catch {
            return fallback;
        }
    }

    function stableId(text) {
        let hash = 0;
        for (let i = 0; i < text.length; i += 1) {
            hash = ((hash << 5) - hash) + text.charCodeAt(i);
            hash |= 0;
        }
        return Math.abs(hash).toString(36);
    }

    function getNameFromUrl(url) {
        try {
            const path = new URL(url).pathname;
            return decodeURIComponent(path.split('/').pop() || '未命名频道');
        } catch {
            return '未命名频道';
        }
    }

    function escapeHtml(value) {
        return String(value || '').replace(/[&<>"']/g, char => ({
            '&': '&amp;',
            '<': '&lt;',
            '>': '&gt;',
            '"': '&quot;',
            "'": '&#39;'
        })[char]);
    }

    function escapeAttr(value) {
        return escapeHtml(value).replace(/`/g, '&#96;');
    }
})();
