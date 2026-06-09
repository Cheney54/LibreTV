import path from 'path';
import express from 'express';
import axios from 'axios';
import cors from 'cors';
import { fileURLToPath } from 'url';
import fs from 'fs';
import crypto from 'crypto';
import dotenv from 'dotenv';
import http from 'http';
import https from 'https';

dotenv.config();

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const config = {
  port: process.env.PORT || 8080,
  password: process.env.PASSWORD || '',
  adminpassword: process.env.ADMINPASSWORD || '',
  corsOrigin: process.env.CORS_ORIGIN || '*',
  timeout: parseInt(process.env.REQUEST_TIMEOUT || '30000'),
  maxRetries: parseInt(process.env.MAX_RETRIES || '2'),
  cacheMaxAge: process.env.CACHE_MAX_AGE || '1d',
  userAgent: process.env.USER_AGENT || 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36',
  debug: process.env.DEBUG === 'true'
};

const httpAgent = new http.Agent({
  keepAlive: true,
  maxSockets: 80,
  maxFreeSockets: 20,
  timeout: 45000
});

const httpsAgent = new https.Agent({
  keepAlive: true,
  maxSockets: 80,
  maxFreeSockets: 20,
  timeout: 45000
});

const log = (...args) => {
  if (config.debug) {
    console.log('[DEBUG]', ...args);
  }
};

const app = express();

app.use(cors({
  origin: config.corsOrigin,
  methods: ['GET', 'POST'],
  allowedHeaders: ['Content-Type', 'Authorization']
}));

app.use((req, res, next) => {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'SAMEORIGIN');
  res.setHeader('X-XSS-Protection', '1; mode=block');
  next();
});

function sha256Hash(input) {
  return new Promise((resolve) => {
    const hash = crypto.createHash('sha256');
    hash.update(input);
    resolve(hash.digest('hex'));
  });
}

async function renderPage(filePath, password) {
  let content = fs.readFileSync(filePath, 'utf8');
  if (password !== '') {
    const sha256 = await sha256Hash(password);
    content = content.replace('{{PASSWORD}}', sha256);
  }
  // 添加ADMINPASSWORD注入
  if (config.adminpassword !== '') {
      const adminSha256 = await sha256Hash(config.adminpassword);
      content = content.replace('{{ADMINPASSWORD}}', adminSha256);
  } 
  return content;
}

app.get(['/', '/index.html', '/player.html', '/live.html'], async (req, res) => {
  try {
    let filePath;
    switch (req.path) {
      case '/player.html':
        filePath = path.join(__dirname, 'player.html');
        break;
      case '/live.html':
        filePath = path.join(__dirname, 'live.html');
        break;
      default: // '/' 和 '/index.html'
        filePath = path.join(__dirname, 'index.html');
        break;
    }
    
    const content = await renderPage(filePath, config.password);
    res.send(content);
  } catch (error) {
    console.error('页面渲染错误:', error);
    res.status(500).send('读取静态页面失败');
  }
});

app.get('/s=:keyword', async (req, res) => {
  try {
    const filePath = path.join(__dirname, 'index.html');
    const content = await renderPage(filePath, config.password);
    res.send(content);
  } catch (error) {
    console.error('搜索页面渲染错误:', error);
    res.status(500).send('读取静态页面失败');
  }
});

function isValidUrl(urlString) {
  try {
    const parsed = new URL(urlString);
    const allowedProtocols = ['http:', 'https:'];
    
    // 从环境变量获取阻止的主机名列表
    const blockedHostnames = (process.env.BLOCKED_HOSTS || 'localhost,127.0.0.1,0.0.0.0,::1').split(',');
    
    // 从环境变量获取阻止的 IP 前缀
    const blockedPrefixes = (process.env.BLOCKED_IP_PREFIXES || '192.168.,10.,172.').split(',');
    
    if (!allowedProtocols.includes(parsed.protocol)) return false;
    if (blockedHostnames.includes(parsed.hostname)) return false;
    
    for (const prefix of blockedPrefixes) {
      if (parsed.hostname.startsWith(prefix)) return false;
    }
    
    return true;
  } catch {
    return false;
  }
}

// 修复反向代理处理过的路径
function getBaseUrl(urlString) {
  const parsed = new URL(urlString);
  const segments = parsed.pathname.split('/').filter(Boolean);
  if (segments.length <= 1) {
    return `${parsed.origin}/`;
  }

  segments.pop();
  return `${parsed.origin}/${segments.join('/')}/`;
}

function resolveUrl(baseUrl, relativeUrl) {
  if (/^https?:\/\//i.test(relativeUrl)) {
    return relativeUrl;
  }

  return new URL(relativeUrl, baseUrl).toString();
}

function rewriteUrlToProxy(targetUrl) {
  return `/proxy/${encodeURIComponent(targetUrl)}`;
}

function isM3u8Response(targetUrl, headers = {}) {
  const contentType = String(headers['content-type'] || headers['Content-Type'] || '').toLowerCase();
  return contentType.includes('application/vnd.apple.mpegurl') ||
    contentType.includes('application/x-mpegurl') ||
    contentType.includes('audio/mpegurl') ||
    /\.m3u8($|\?)/i.test(targetUrl);
}

function streamToString(stream) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    stream.on('data', chunk => chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)));
    stream.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    stream.on('error', reject);
  });
}

function processKeyLine(line, baseUrl) {
  return line.replace(/URI="([^"]+)"/, (match, uri) => {
    return `URI="${rewriteUrlToProxy(resolveUrl(baseUrl, uri))}"`;
  });
}

function processMapLine(line, baseUrl) {
  return line.replace(/URI="([^"]+)"/, (match, uri) => {
    return `URI="${rewriteUrlToProxy(resolveUrl(baseUrl, uri))}"`;
  });
}

function processMediaPlaylist(targetUrl, content) {
  const baseUrl = getBaseUrl(targetUrl);
  return content.split('\n').map((rawLine) => {
    const line = rawLine.trim();
    if (!line) return rawLine;
    if (line.startsWith('#EXT-X-KEY')) return processKeyLine(rawLine, baseUrl);
    if (line.startsWith('#EXT-X-MAP')) return processMapLine(rawLine, baseUrl);
    if (line.startsWith('#')) return rawLine;

    return rewriteUrlToProxy(resolveUrl(baseUrl, line));
  }).join('\n');
}

function processMasterPlaylist(targetUrl, content) {
  const baseUrl = getBaseUrl(targetUrl);
  return content.split('\n').map((rawLine) => {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) {
      if (line.startsWith('#')) {
        return rawLine.replace(/URI="([^"]+)"/g, (match, uri) => {
          return `URI="${rewriteUrlToProxy(resolveUrl(baseUrl, uri))}"`;
        });
      }
      return rawLine;
    }

    return rewriteUrlToProxy(resolveUrl(baseUrl, line));
  }).join('\n');
}

function processM3u8Content(targetUrl, content) {
  if (!content.includes('#EXT-X-')) {
    return content;
  }

  if (content.includes('#EXT-X-STREAM-INF') || content.includes('#EXT-X-MEDIA:')) {
    return processMasterPlaylist(targetUrl, content);
  }

  return processMediaPlaylist(targetUrl, content);
}

app.use('/proxy', (req, res, next) => {
  const targetUrl = req.url.replace(/^\//, '').replace(/(https?:)\/([^/])/, '$1//$2');
  req.url = '/' + encodeURIComponent(targetUrl);
  next();
});

// 代理路由
app.get('/proxy/:encodedUrl', async (req, res) => {
  try {
    const encodedUrl = req.params.encodedUrl;
    const targetUrl = decodeURIComponent(encodedUrl);

    // 安全验证
    if (!isValidUrl(targetUrl)) {
      return res.status(400).send('无效的 URL');
    }

    log(`代理请求: ${targetUrl}`);

    // 添加请求超时和重试逻辑
    const maxRetries = config.maxRetries;
    let retries = 0;
    
    const makeRequest = async () => {
      try {
        return await axios({
          method: 'get',
          url: targetUrl,
          responseType: 'stream',
          timeout: config.timeout,
          httpAgent,
          httpsAgent,
          headers: {
            'User-Agent': config.userAgent,
            'Connection': 'keep-alive'
          }
        });
      } catch (error) {
        if (retries < maxRetries) {
          retries++;
          log(`重试请求 (${retries}/${maxRetries}): ${targetUrl}`);
          return makeRequest();
        }
        throw error;
      }
    };

    const response = await makeRequest();

    // 转发响应头（过滤敏感头）
    const headers = { ...response.headers };
    const sensitiveHeaders = (
      process.env.FILTERED_HEADERS || 
      'content-security-policy,cookie,set-cookie,x-frame-options,access-control-allow-origin'
    ).split(',');
    
    sensitiveHeaders.forEach(header => delete headers[header]);
    res.set(headers);

    if (isM3u8Response(targetUrl, response.headers)) {
      delete headers['content-length'];
      delete headers['Content-Length'];
      res.removeHeader('Content-Length');
      res.set({
        ...headers,
        'Content-Type': 'application/vnd.apple.mpegurl;charset=utf-8'
      });
      const content = await streamToString(response.data);
      return res.send(processM3u8Content(targetUrl, content));
    }

    req.on('close', () => {
      if (!res.writableEnded && response.data.destroy) {
        response.data.destroy();
      }
    });
    response.data.on('error', (streamError) => {
      log(`上游流中断: ${streamError.message}`);
      if (!res.headersSent) {
        res.status(502).send(`视频流中断: ${streamError.message}`);
      } else {
        res.end();
      }
    });

    return response.data.pipe(res);

  } catch (error) {
    console.error('代理请求错误:', error.message);
    if (error.response) {
      res.status(error.response.status || 500);
      error.response.data.pipe(res);
    } else {
      res.status(500).send(`请求失败: ${error.message}`);
    }
  }
});

app.use(express.static(path.join(__dirname), {
  maxAge: config.cacheMaxAge
}));

app.use((err, req, res, next) => {
  console.error('服务器错误:', err);
  res.status(500).send('服务器内部错误');
});

app.use((req, res) => {
  res.status(404).send('页面未找到');
});

// 启动服务器
app.listen(config.port, () => {
  console.log(`服务器运行在 http://localhost:${config.port}`);
  if (config.password !== '') {
    console.log('用户登录密码已设置');
  }
  if (config.adminpassword !== '') {
    console.log('管理员登录密码已设置');
  }
  if (config.debug) {
    console.log('调试模式已启用');
    console.log('配置:', { ...config, password: config.password ? '******' : '', adminpassword: config.adminpassword? '******' : '' });
  }
});
