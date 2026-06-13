// 测试脚本：模拟查询 MO9560 的 FSM 返回内容
// 用 Node.js 运行：node test_ticket_mo9560.js

const https = require('https');

const URL = 'https://www.fsm.gov.mo/webticket/Webform1.aspx';

function httpGet(url) {
    return new Promise((resolve, reject) => {
        https.get(url, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
                'Accept': 'text/html,application/xhtml+xml',
                'Accept-Language': 'zh-CN,zh;q=0.9'
            }
        }, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => resolve({ html: data, cookies: res.headers['set-cookie'] }));
        }).on('error', reject);
    });
}

function httpPost(url, body, cookies) {
    return new Promise((resolve, reject) => {
        const postData = new URLSearchParams(body).toString();
        const options = new URL(url);
        const req = https.request({
            hostname: options.hostname,
            path: options.pathname + options.search,
            method: 'POST',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded',
                'Content-Length': Buffer.byteLength(postData),
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
                'Accept': 'text/html,application/xhtml+xml',
                'Accept-Language': 'zh-CN,zh;q=0.9',
                'Cookie': cookies
            }
        }, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => resolve(data));
        });
        req.on('error', reject);
        req.write(postData);
        req.end();
    });
}

function extractValue(html, name) {
    // 从 HTML 中提取 __VIEWSTATE 等隐藏字段
    const match = html.match(new RegExp(`name="${name}"\\s+value="([^"]+)"`));
    return match ? match[1] : '';
}

async function queryPlate(plate) {
    console.log(`[GET] ${URL}`);
    const { html: html0, cookies } = await httpGet(URL);
    const cookieStr = (cookies || []).map(c => c.split(';')[0]).join('; ');
    
    const viewState = extractValue(html0, '__VIEWSTATE');
    const viewStateGenerator = extractValue(html0, '__VIEWSTATEGENERATOR');
    const eventValidation = extractValue(html0, '__EVENTVALIDATION');
    
    console.log(`[PARSED] __VIEWSTATE length: ${viewState.length}`);
    console.log(`[PARSED] __EVENTVALIDATION length: ${eventValidation.length}`);
    
    // 构造 POST 数据（需要查看表单字段名）
    // 先输出 HTML 中车牌输入框的名称
    const inputMatch = html0.match(/<input[^>]+type="text"[^>]+name="([^"]+)"[^>]*id="[^"]*plate"/i);
    console.log('[DEBUG] Plate input name:', inputMatch ? inputMatch[1] : 'NOT FOUND');
    
    // 保存 GET 页面用于分析
    require('fs').writeFileSync('test_get_mo9560.html', html0);
    console.log('[SAVED] GET response saved to test_get_mo9560.html');
}

queryPlate('MO9560').catch(console.error);
