// Cloudflare Functions API - 获取访问日志
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
  
  // 检查是否有管理员权限（简单验证）
  const authHeader = request.headers.get('authorization');
  const adminPassword = env.ADMINPASSWORD || '';
  
  if (!adminPassword || !authHeader || !authHeader.startsWith('Bearer ')) {
    return new Response(JSON.stringify({ error: '需要管理员权限' }), {
      status: 401,
      headers: {
        'Content-Type': 'application/json',
        'Access-Control-Allow-Origin': '*'
      }
    });
  }
  
  try {
    // 从 KV 存储获取访问日志
    if (env.VISIT_LOGS_KV) {
      const logs = [];
      
      // 获取最近的100条记录
      const listResult = await env.VISIT_LOGS_KV.list({ limit: 100 });
      
      for (const key of listResult.keys) {
        const logData = await env.VISIT_LOGS_KV.get(key.name);
        if (logData) {
          logs.push(JSON.parse(logData));
        }
      }
      
      // 按时间倒序排列
      logs.sort((a, b) => new Date(b.timestamp) - new Date(a.timestamp));
      
      return new Response(JSON.stringify({
        success: true,
        logs,
        total: logs.length
      }), {
        status: 200,
        headers: {
          'Content-Type': 'application/json',
          'Access-Control-Allow-Origin': '*'
        }
      });
    } else {
      return new Response(JSON.stringify({
        error: 'KV存储未配置'
      }), {
        status: 500,
        headers: {
          'Content-Type': 'application/json',
          'Access-Control-Allow-Origin': '*'
        }
      });
    }
  } catch (error) {
    return new Response(JSON.stringify({
      error: '获取日志失败',
      details: error.message
    }), {
      status: 500,
      headers: {
        'Content-Type': 'application/json',
        'Access-Control-Allow-Origin': '*'
      }
    });
  }
} 