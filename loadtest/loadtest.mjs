#!/usr/bin/env node
/**
 * 决策接口压测：模拟业务系统在高峰期的批量准入查询。
 *
 * 用法：
 *   node loadtest/loadtest.mjs --url http://localhost:8080 --key demo-key-7f3a9c1e5b2d4f60 \
 *        --concurrency 200 --duration 30 --subjects 2000 --purposes 4
 *
 * 设计：
 * - 每个 worker 一条 keep-alive 长连接，避免端口耗尽与握手开销；
 * - 用户 ID 与用途在预热阶段确定（混合热/冷用户，模拟真实分布）；
 * - 统计 p50/p90/p99/p999、吞吐、错误率，以及服务端缓存命中率。
 * 只使用 Node 内置模块，无需安装依赖。
 */
import http from 'node:http';
import { performance } from 'node:perf_hooks';

const args = parseArgs(process.argv.slice(2));
const BASE = new URL(args.url ?? 'http://localhost:8080');
const API_KEY = args.key ?? 'demo-key-7f3a9c1e5b2d4f60';
const CONCURRENCY = Number(args.concurrency ?? 200);
const DURATION_S = Number(args.duration ?? 20);
const SUBJECT_COUNT = Number(args.subjects ?? 2000);
const PURPOSES_PER_REQ = Number(args.purposes ?? 4);
const PURPOSES = (args['purpose-list'] ??
    'marketing_email,personalized_recommendation,analytics_improvement,location_services,' +
    'third_party_ads,face_recognition').split(',');
const WARMUP_S = Number(args.warmup ?? 3);
const SUBJECT_PREFIX = args.prefix ?? 'U';
const SUBJECT_BASE = Number(args['subject-base'] ?? 100001);

const agent = new http.Agent({ keepAlive: true, maxSockets: CONCURRENCY, noDelay: true });

function parseArgs(argv) {
    const out = {};
    for (let i = 0; i < argv.length; i++) {
        const token = argv[i];
        if (!token.startsWith('--')) continue;
        const key = token.slice(2);
        const value = argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[++i] : 'true';
        out[key] = value;
    }
    return out;
}

// 热点用户池覆盖整个用户范围（而非只压前 N 个，否则会集中命中某一
// 政策版本的样本，使允许率失真）
const HOT_POOL_SIZE = Math.max(1, Math.floor(SUBJECT_COUNT * 0.9));
const hotPool = (() => {
    const all = Array.from({ length: SUBJECT_COUNT }, (_, i) => i);
    // Fisher–Yates，固定种子使每次运行可比
    let seed = 20261117;
    const rand = () => {
        seed ^= seed << 13; seed >>>= 0;
        seed ^= seed >>> 17;
        seed ^= seed << 5; seed >>>= 0;
        return seed / 0x100000000;
    };
    for (let i = all.length - 1; i > 0; i--) {
        const j = Math.floor(rand() * (i + 1));
        [all[i], all[j]] = [all[j], all[i]];
    }
    return all.slice(0, HOT_POOL_SIZE);
})();

function subjectAt(index) {
    return SUBJECT_PREFIX + String(SUBJECT_BASE + index).padStart(6, '0');
}

function pickSubject() {
    // 90% 命中热点用户，10% 走全量长尾
    const index = Math.random() < 0.9
        ? hotPool[Math.floor(Math.random() * hotPool.length)]
        : Math.floor(Math.random() * SUBJECT_COUNT);
    return subjectAt(index);
}

function pickPurposes() {
    if (PURPOSES_PER_REQ >= PURPOSES.length) return PURPOSES;
    const start = Math.floor(Math.random() * (PURPOSES.length - PURPOSES_PER_REQ + 1));
    return PURPOSES.slice(start, start + PURPOSES_PER_REQ);
}

function request(subjectId, purposes) {
    const payload = JSON.stringify({
        subjectId,
        purposes,
        requestId: `lt-${process.pid}-${Math.random().toString(36).slice(2, 10)}`,
    });
    const started = performance.now();
    return new Promise((resolve) => {
        const req = http.request({
            host: BASE.hostname,
            port: BASE.port || 80,
            path: '/api/v1/decisions',
            method: 'POST',
            agent,
            headers: {
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(payload),
                'X-API-Key': API_KEY,
                Connection: 'keep-alive',
            },
        }, (res) => {
            let body = '';
            res.setEncoding('utf8');
            res.on('data', (chunk) => { body += chunk; });
            res.on('end', () => {
                resolve({
                    ms: performance.now() - started,
                    status: res.statusCode,
                    allowed: body.includes('"allowed":true'),
                    cached: body.includes('"cached":true'),
                });
            });
        });
        req.on('error', (err) => {
            resolve({ ms: performance.now() - started, status: 0, error: err.code || err.message });
        });
        req.setTimeout(15_000, () => req.destroy(new Error('timeout')));
        req.end(payload);
    });
}

async function phase(name, seconds, collect) {
    const deadline = performance.now() + seconds * 1000;
    const workers = [];
    for (let w = 0; w < CONCURRENCY; w++) {
        workers.push((async () => {
            while (performance.now() < deadline) {
                const subject = pickSubject();
                const purposes = pickPurposes();
                const result = await request(subject, purposes);
                if (collect) collect(result);
            }
        })());
    }
    await Promise.all(workers);
}

function percentile(sorted, p) {
    if (sorted.length === 0) return 0;
    const index = Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length));
    return sorted[index];
}

function format(n, digits = 1) {
    return Number(n).toFixed(digits);
}

async function fetchServerStats() {
    try {
        // 管理端需要登录，压测脚本改为读取 actuator metrics 中的自有指标
        const metrics = await getJson('/actuator/metrics');
        return metrics ? metrics.names?.slice(0, 0) : null;
    } catch {
        return null;
    }
}

function getJson(path) {
    return new Promise((resolve, reject) => {
        http.get({ host: BASE.hostname, port: BASE.port || 80, path, agent }, (res) => {
            let body = '';
            res.on('data', (c) => { body += c; });
            res.on('end', () => {
                try { resolve(JSON.parse(body)); } catch { resolve(null); }
            });
        }).on('error', reject);
    });
}

async function main() {
    console.log(`\n同意决策接口压测`);
    console.log(`  目标        ${BASE.origin}/api/v1/decisions`);
    console.log(`  并发        ${CONCURRENCY}`);
    console.log(`  时长        ${DURATION_S}s（另有 ${WARMUP_S}s 预热）`);
    console.log(`  用户池      ${SUBJECT_COUNT}，每请求 ${PURPOSES_PER_REQ} 个用途\n`);

    process.stdout.write('预热中… ');
    const warmupCount = { ok: 0, fail: 0 };
    await phase('warmup', WARMUP_S, (r) => {
        if (r.status === 200) warmupCount.ok++; else warmupCount.fail++;
    });
    console.log(`完成（${warmupCount.ok} 成功 / ${warmupCount.fail} 失败）`);

    const latencies = [];
    const perPurpose = new Map();
    let ok = 0, fail = 0, cached = 0, allowed = 0;
    const statuses = new Map();
    const errorSamples = new Map();

    const startedAt = performance.now();
    await phase('run', DURATION_S, (r) => {
        latencies.push(r.ms);
        statuses.set(r.status, (statuses.get(r.status) ?? 0) + 1);
        if (r.status === 200) {
            ok++;
            if (r.cached) cached++;
            if (r.allowed) allowed++;
        } else {
            fail++;
            if (r.error && errorSamples.size < 5) errorSamples.set(r.error, (errorSamples.get(r.error) ?? 0) + 1);
        }
    });
    const elapsed = (performance.now() - startedAt) / 1000;

    latencies.sort((a, b) => a - b);
    const total = ok + fail;
    const rps = total / elapsed;

    console.log(`\n结果（${format(elapsed, 2)}s，${total} 次请求）`);
    console.log(`  吞吐        ${format(rps)} req/s`);
    console.log(`  成功/失败   ${ok} / ${fail}${fail ? `  (${format((fail / total) * 100, 2)}%)` : ''}`);
    console.log(`  延迟 p50    ${format(percentile(latencies, 50))} ms`);
    console.log(`  延迟 p90    ${format(percentile(latencies, 90))} ms`);
    console.log(`  延迟 p99    ${format(percentile(latencies, 99))} ms`);
    console.log(`  延迟 p999   ${format(percentile(latencies, 99.9))} ms`);
    console.log(`  最大延迟    ${format(latencies[latencies.length - 1])} ms`);
    console.log(`  缓存命中    ${format((cached / Math.max(1, ok)) * 100)}%`);
    console.log(`  判定允许    ${format((allowed / Math.max(1, ok)) * 100)}%`);
    const statusLine = [...statuses.entries()].map(([s, n]) => `${s}:${n}`).join('  ');
    console.log(`  状态码      ${statusLine}`);
    if (errorSamples.size) {
        console.log(`  错误样例    ${[...errorSamples.entries()].map(([e, n]) => `${e}×${n}`).join(', ')}`);
    }
    const stats = await fetchServerStats();
    if (stats) console.log(`  Actuator    可用`);
    console.log('');

    process.exit(fail === 0 ? 0 : 1);
}

await main();
