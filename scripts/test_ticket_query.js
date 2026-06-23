// test_ticket_query.js — 本地验证 FSM 告票查询解析逻辑
// 用法：node test_ticket_query.js [车牌1] [车牌2] ...

const https = require('https');
const fs = require('fs');

// ── 配置 ─────────────────────────────────────────────
const PLATES = process.argv.slice(2).length > 0
    ? process.argv.slice(2)
    : ['MO9560', 'AA5186', 'AC6198', 'MU3844'];

const HOST = 'www.fsm.gov.mo';
const PATH = '/webticket/Webform1.aspx?carClass=L&Lang=C';
const BTN_OK = '確定';

// ── 工具函数 ────────────────────────────────────────
function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

function httpGet(path, cookie) {
    return new Promise((resolve, reject) => {
        const headers = {
            'Host': HOST,
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
            'Accept': 'text/html,application/xhtml+xml',
            'Connection': 'keep-alive'
        };
        if (cookie) headers['Cookie'] = cookie;

        const options = {
            hostname: HOST,
            path: path,
            method: 'GET',
            headers: headers,
            rejectUnauthorized: false
        };

        const req = https.request(options, (res) => {
            // 处理 302 重定向
            if (res.statusCode === 302 || res.statusCode === 301) {
                const location = res.headers.location;
                if (location) {
                    const nextPath = location.startsWith('/') ? location : PATH;
                    console.log(`    → 重定向到 ${nextPath}`);
                    return resolve(httpGet(nextPath, cookie));
                }
            }

            let data = '';
            res.on('data', chunk => { data += chunk; });
            res.on('end', () => {
                const setCookie = res.headers['set-cookie'];
                const newCookie = setCookie ? setCookie.map(c => c.split(';')[0]).join('; ') : null;
                resolve({ status: res.statusCode, body: data, cookie: newCookie });
            });
        });

        req.on('error', reject);
        req.end();
    });
}

function httpPost(path, body, cookie) {
    return new Promise((resolve, reject) => {
        const headers = {
            'Host': HOST,
            'Content-Type': 'application/x-www-form-urlencoded',
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
            'Accept': 'text/html,application/xhtml+xml',
            'Connection': 'keep-alive',
            'Content-Length': Buffer.byteLength(body)
        };
        if (cookie) headers['Cookie'] = cookie;

        const options = {
            hostname: HOST,
            path: path,
            method: 'POST',
            headers: headers,
            rejectUnauthorized: false
        };

        const req = https.request(options, (res) => {
            if (res.statusCode === 302 || res.statusCode === 301) {
                const location = res.headers.location;
                if (location) {
                    const nextPath = location.startsWith('/') ? location : PATH;
                    return resolve(httpGet(nextPath, cookie));
                }
            }

            let data = '';
            res.on('data', chunk => { data += chunk; });
            res.on('end', () => resolve({ status: res.statusCode, body: data }));
        });

        req.on('error', reject);
        req.write(body);
        req.end();
    });
}

function extractHiddenFields(html) {
    const fields = {};
    // 匹配 name="xxx" value="yyy"（支持单/双引号，跨行）
    const re = /name="(__[^"]+)"\s+value="([^"]*)"/g;
    let m;
    while ((m = re.exec(html)) !== null) {
        fields[m[1]] = m[2];
    }
    // 如果上面没匹配到，尝试 id= 格式
    if (Object.keys(fields).length === 0) {
        const re2 = /id="(__[^"]+)"[^>]*value="([^"]*)"/g;
        while ((m = re2.exec(html)) !== null) {
            fields[m[1]] = m[2];
        }
    }
    return fields;
}

function parseResponse(html, plate) {
    // 提取车牌
    const plateMatch = html.match(/id="lbGetNum"[^>]*>([^<]*)</i);
    const plateNumber = plateMatch ? plateMatch[1].trim() : '';

    // 提取车型
    const imgSrcMatch = html.match(/id="Image1"[^>]*src="([^"]*)"/i);
    const imgSrc = imgSrcMatch ? imgSrcMatch[1].toLowerCase() : '';
    let carType = '---';
    if (imgSrc.includes('newcar')) carType = '新汽車';
    else if (imgSrc.includes('car')) carType = '汽車';
    else if (imgSrc.includes('bike') || imgSrc.includes('motor')) carType = '電單車';
    else {
        const carLabelMatch = html.match(/id="Label2"[^>]*>([^<]*)</i);
        if (carLabelMatch) carType = carLabelMatch[1].trim();
    }

    // 三层检查（对齐 TicketChecker.kt）
    const msgTextMatch = html.match(/id="lbMsgText"[^>]*>([\s\S]*?)</i);
    const msgText = msgTextMatch ? msgTextMatch[1].trim() : '';

    const noTicket2Match = html.match(/id="lbNoTicket2"[^>]*>([\s\S]*?)</i);
    const noTicket2 = noTicket2Match ? noTicket2Match[1].trim() : '';

    const hasTicketHtml = html.includes('有違例紀錄') || html.includes('有违例记录');
    const hasNoTicketHtml = html.includes('沒有違例紀錄');

    let message = '';
    if (msgText) {
        message = msgText;
    } else if (noTicket2) {
        message = noTicket2;
    } else if (hasNoTicketHtml) {
        message = '沒有違例紀錄';
    } else if (hasTicketHtml) {
        message = '有違例紀錄';
    } else {
        message = '查無資料';
    }

    const hasTicket = message.includes('有違例紀錄');

    return {
        plate,
        plateNumber,
        carType,
        hasTicket,
        message
    };
}

// ── 主逻辑 ────────────────────────────────────────────
async function queryPlate(plate) {
    try {
        console.log(`  [${plate}] GET ${PATH}`);
        const getRes = await httpGet(PATH, null);

        if (getRes.status !== 200) {
            return { plate, error: `GET failed: HTTP ${getRes.status}` };
        }

        // 保存 GET 响应用于调试
        fs.writeFileSync(`debug_${plate}_get.html`, getRes.body);
        console.log(`  [${plate}] GET OK (${getRes.body.length} bytes)`);

        const fields = extractHiddenFields(getRes.body);
        console.log(`  [${plate}] __VIEWSTATE: ${fields['__VIEWSTATE'] ? 'OK' : 'MISSING'}, __EVENTVALIDATION: ${fields['__EVENTVALIDATION'] ? 'OK' : 'MISSING'}`);

        if (!fields['__VIEWSTATE'] || !fields['__EVENTVALIDATION']) {
            console.log(`  [${plate}] 警告：无法提取表单字段，保存 HTML 到 debug_${plate}_get.html`);
            return { plate, error: 'Failed to extract __VIEWSTATE/__EVENTVALIDATION' };
        }

        const cookie = getRes.cookie;
        console.log(`  [${plate}] Cookie: ${cookie ? 'OK' : 'NONE'}`);

        // POST 提交查询
        const postBody = `__EVENTTARGET` +
            `&__EVENTARGUMENT` +
            `&__VIEWSTATE=${encodeURIComponent(fields['__VIEWSTATE'])}` +
            `&__EVENTVALIDATION=${encodeURIComponent(fields['__EVENTVALIDATION'])}` +
            `&Calculator=${encodeURIComponent(plate)}` +
            `&btnOk=${encodeURIComponent(BTN_OK)}`;

        console.log(`  [${plate}] POST ${PATH}`);
        const postRes = await httpPost(PATH, postBody, cookie);

        if (postRes.status !== 200) {
            return { plate, error: `POST failed: HTTP ${postRes.status}` };
        }

        // 保存 POST 响应用于调试
        fs.writeFileSync(`debug_${plate}_post.html`, postRes.body);
        console.log(`  [${plate}] POST OK (${postRes.body.length} bytes)`);

        // 解析响应
        const result = parseResponse(postRes.body, plate);
        return result;

    } catch (e) {
        return { plate, error: `Exception: ${e.message}` };
    }
}

// ── 入口 ──────────────────────────────────────────────
(async () => {
    console.log('=== FSM 告票查询本地验证 ===');
    console.log(`测试车牌：${PLATES.join(', ')}`);
    console.log('');

    const results = [];
    for (const plate of PLATES) {
        console.log(`[${plate}] 查询中...`);
        const result = await queryPlate(plate);
        results.push(result);

        if (result.error) {
            console.log(`[${plate}] ❌ 失败：${result.error}`);
        } else {
            const icon = result.hasTicket ? '🔴' : '🟢';
            console.log(`[${plate}] ${icon} ${result.message}（车型：${result.carType}）`);
        }
        console.log('');

        await sleep(1000);  // 避免请求过快
    }

    console.log('=== 汇总结果 ===');
    for (const r of results) {
        if (r.error) {
            console.log(`${r.plate}，查询失败：${r.error}`);
        } else {
            const icon = r.hasTicket ? '🔴' : '🟢';
            console.log(`${r.plate} ${icon} ${r.message}`);
        }
    }

    console.log('');
    console.log('调试文件已保存：');
    console.log('  - debug_${plate}_get.html（GET 响应）');
    console.log('  - debug_${plate}_post.html（POST 响应）');
})();
