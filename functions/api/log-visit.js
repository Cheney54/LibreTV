// Cloudflare Functions API - 访问日志记录
export async function onRequest(context) {
  const { request, env } = context;
  
  // 处理 OPTIONS 预检请求
  if (request.method === 'OPTIONS') {
    return new Response(null, {
      status: 204,
      headers: {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
        'Access-Control-Allow-Headers': '*'
      }
    });
  }
  
  // 获取客户端IP
  const clientIP = request.headers.get('cf-connecting-ip') || 
                   request.headers.get('x-forwarded-for') || 
                   request.headers.get('x-real-ip') || 
                   'unknown';
  
  // 获取其他访问信息
  const userAgent = request.headers.get('user-agent') || 'unknown';
  const referrer = request.headers.get('referer') || 'direct';
  const acceptLanguage = request.headers.get('accept-language') || 'unknown';
  const timestamp = new Date().toISOString();
  const url = request.url;
  
  // 获取请求体中的额外信息
  let additionalInfo = {};
  if (request.method === 'POST') {
    try {
      const body = await request.json();
      additionalInfo = body;
    } catch (error) {
      // 忽略JSON解析错误
    }
  }
  
  // 构建日志条目
  const logEntry = {
    timestamp,
    ip: clientIP,
    userAgent,
    referrer,
    acceptLanguage,
    url,
    method: request.method,
    ...additionalInfo
  };
  
  // 记录到控制台（Cloudflare Workers 会自动记录到日志）
  console.log(`[访问日志] ${JSON.stringify(logEntry)}`);
  
  // 如果有 KV 存储，也可以存储到 KV 中
  if (env.VISIT_LOGS_KV) {
    try {
      const logKey = `visit_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
      await env.VISIT_LOGS_KV.put(logKey, JSON.stringify(logEntry), {
        expirationTtl: 86400 * 30 // 30天过期
      });
    } catch (error) {
      console.error('存储访问日志到KV失败:', error);
    }
  }
  
  // 返回成功响应
  return new Response(JSON.stringify({ success: true, message: '访问已记录' }), {
    status: 200,
    headers: {
      'Content-Type': 'application/json',
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': '*'
    }
  });
} 