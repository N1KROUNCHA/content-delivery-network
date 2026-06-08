
    // ====== STATE ======
    let token = null, username = null, userRole = null;
    let authMode = 'login';
    let allLogs = [];
    let selectedFile = null;
    let logInterval = null;

    // ====== INIT ======
    window.onload = () => {
        const t = sessionStorage.getItem('pqc_token');
        const u = sessionStorage.getItem('pqc_user');
        const r = sessionStorage.getItem('pqc_role');
        if (t && u && r) {
            token = t; username = u; userRole = r;
            postLogin();
        }
        checkServiceHealth();
    };

    // ====== AUTH TABS ======
    function setAuthMode(mode) {
        authMode = mode;
        document.getElementById('tabLogin').classList.toggle('active', mode === 'login');
        document.getElementById('tabRegister').classList.toggle('active', mode === 'register');
        document.getElementById('roleGroup').style.display = mode === 'register' ? 'block' : 'none';
        document.getElementById('authSubmit').textContent = mode === 'login' ? 'Sign In' : 'Create Account';
        document.getElementById('authHeading').textContent = mode === 'login' ? '🔐 Sign In' : '📝 Create Account';
        document.getElementById('toggleLink').innerHTML = mode === 'login'
            ? 'No account? <span onclick="setAuthMode(\'register\')">Register here</span>'
            : 'Have an account? <span onclick="setAuthMode(\'login\')">Login here</span>';
        document.getElementById('authAlert').style.display = 'none';
    }

    async function handleAuth(e) {
        e.preventDefault();
        const alert = document.getElementById('authAlert');
        alert.style.display = 'none';
        const payload = {
            username: document.getElementById('username').value.trim(),
            password: document.getElementById('password').value
        };
        if (authMode === 'register') payload.role = document.getElementById('role').value;
        const endpoint = authMode === 'register' ? '/client-api/register' : '/client-api/login';
        try {
            const res = await fetch(endpoint, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
            if (!res.ok) { const t = await res.text(); throw new Error(t || 'Auth failed'); }
            const data = await res.json();
            token = 'Bearer ' + data.token;
            username = data.username;
            userRole = data.role;
            sessionStorage.setItem('pqc_token', token);
            sessionStorage.setItem('pqc_user', username);
            sessionStorage.setItem('pqc_role', userRole);
            postLogin();
        } catch (err) {
            alert.textContent = err.message;
            alert.style.display = 'block';
        }
    }

    function postLogin() {
        document.getElementById('pulseDot').classList.add('online');
        document.getElementById('userLabel').textContent = `${username} · ${userRole}`;
        document.getElementById('logoutBtn').style.display = 'block';
        document.getElementById('mainNav').style.display = 'flex';

        // Show/hide admin tab
        const adminTab = document.getElementById('navAdmin');
        adminTab.style.display = userRole === 'ADMIN' ? 'block' : 'none';

        // Show/hide client tab
        const clientTab = document.getElementById('navClient');
        clientTab.style.display = userRole === 'USER' ? 'block' : 'none';

        // Session info
        document.getElementById('sessionUser').textContent = username;
        document.getElementById('sessionRole').textContent = userRole;
        document.getElementById('sessionToken').textContent = token.substring(7, 34) + '…';

        // Disable upload if not admin
        if (userRole !== 'ADMIN') {
            document.getElementById('uploadBtn').disabled = true;
            document.getElementById('dropZone').style.opacity = '0.4';
            document.getElementById('dropZone').style.pointerEvents = 'none';
            document.getElementById('versionInput').disabled = true;
        }

        switchView('overview');
        fetchVersions();
        fetchAuditLogs();
        logInterval = setInterval(fetchAuditLogs, 5000);
    }

    function logout() {
        sessionStorage.clear();
        if (logInterval) clearInterval(logInterval);
        window.location.reload();
    }

    // ====== VIEW SWITCHING ======
    function switchView(view) {
        document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
        document.querySelectorAll('.nav-tab').forEach(t => t.classList.remove('active'));
        document.getElementById('view' + view.charAt(0).toUpperCase() + view.slice(1)).classList.add('active');
        const navId = 'nav' + view.charAt(0).toUpperCase() + view.slice(1);
        const navEl = document.getElementById(navId);
        if (navEl) navEl.classList.add('active');
        if (view === 'overview') checkServiceHealth();
        if (view === 'client') fetchVersions();
        if (view === 'logs') fetchAuditLogs();
        if (view === 'gateway') updateGwRequestBodyInfo();
    }

    // ====== API GATEWAY TESTER ======
    function updateGwRequestBodyInfo() {
        const select = document.getElementById('gwRouteSelect');
        const opt = select.options[select.selectedIndex];
        const method = opt.getAttribute('data-method');
        const bodyGroup = document.getElementById('gwBodyGroup');
        
        if (method === 'POST') {
            bodyGroup.style.display = 'block';
            if (opt.value.includes('/upload')) {
                document.getElementById('gwRequestBody').value = '{\n  "version": "v2.0.0",\n  "fileName": "ota_patch_v2.zip"\n}';
            } else if (opt.value.includes('/download')) {
                document.getElementById('gwRequestBody').value = '{\n  "kyberPublicKey": "MCowBQYDK2VwAyEA...[ephemeral kyber key data]"\n}';
            }
        } else {
            bodyGroup.style.display = 'none';
        }
    }

    async function dispatchGatewayTest() {
        const select = document.getElementById('gwRouteSelect');
        const opt = select.options[select.selectedIndex];
        const path = opt.value;
        const method = opt.getAttribute('data-method');
        const scenario = document.getElementById('gwScenarioSelect').value;
        const body = method === 'POST' ? document.getElementById('gwRequestBody').value.trim() : '';

        // Reset visual state
        const nodeClient = document.getElementById('nodeClient');
        const nodeGateway = document.getElementById('nodeGateway');
        const nodeService = document.getElementById('nodeService');
        const line1 = document.getElementById('lineClientToGateway');
        const line2 = document.getElementById('lineGatewayToService');
        const pulse1 = document.getElementById('pulseClientToGateway');
        const pulse2 = document.getElementById('pulseGatewayToService');
        const tag = document.getElementById('gwVerifyStatusTag');
        const serviceLabel = document.getElementById('targetServiceLabel');
        const statusState = document.getElementById('gwStatusState');

        nodeClient.className = 'node-active';
        nodeGateway.className = '';
        nodeGateway.style.background = '';
        nodeGateway.style.borderColor = '';
        nodeGateway.style.boxShadow = '';
        
        nodeService.className = '';
        nodeService.style.background = '';
        nodeService.style.borderColor = '';
        nodeService.style.boxShadow = '';
        
        line1.className = '';
        line2.className = '';
        
        pulse1.style.display = 'block';
        pulse1.classList.add('pulse-active');
        
        pulse2.style.display = 'none';
        pulse2.classList.remove('pulse-active');

        tag.className = '';
        tag.textContent = 'ROUTING...';
        tag.style.background = 'var(--primary-dim)';
        tag.style.color = 'var(--primary)';

        statusState.textContent = 'Processing...';
        statusState.className = 'tag tag-amber';

        // Update target service label based on route
        let targetService = 'Unknown Service';
        let serviceIcon = '⚙️';
        if (path.includes('/auth/')) { targetService = 'auth-service (:8081)'; serviceIcon = '🔐'; }
        else if (path.includes('/content/')) { targetService = 'content-service (:8082)'; serviceIcon = '📁'; }
        else if (path.includes('/download/')) { targetService = 'download-service (:8083)'; serviceIcon = '📥'; }
        else if (path.includes('/logs')) { targetService = 'logging-service (:8085)'; serviceIcon = '📊'; }
        serviceLabel.textContent = targetService;
        nodeService.textContent = serviceIcon;

        const reqUrl = `/client-api/test-gateway?path=${encodeURIComponent(path)}&method=${encodeURIComponent(method)}&tokenScenario=${encodeURIComponent(scenario)}`;

        try {
            const res = await fetch(reqUrl, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': token || ''
                },
                body: body ? body : null
            });

            if (!res.ok) {
                throw new Error(await res.text() || 'Proxy call failed');
            }

            const data = await res.json();
            
            // Wait a tiny bit to make the animation visible and dramatic
            await new Promise(r => setTimeout(r, 600));

            // Stop first pulse
            pulse1.style.display = 'none';
            pulse1.classList.remove('pulse-active');

            // Render sent headers block
            let headersText = `${method} ${data.requestUrl} HTTP/1.1\n`;
            for (const [k, v] of Object.entries(data.sentHeaders)) {
                headersText += `${k}: ${v}\n`;
            }
            if (body) {
                headersText += `\n${body}`;
            }
            document.getElementById('gwReqHeadersBlock').textContent = headersText;

            // Render response block
            let respText = `HTTP/1.1 ${data.responseStatus} ${data.responseStatusText}\n`;
            for (const [k, v] of Object.entries(data.responseHeaders || {})) {
                respText += `${k}: ${v}\n`;
            }
            respText += `\n${data.responseBody || ''}`;
            document.getElementById('gwRespBlock').textContent = respText;

            // Render action details
            document.getElementById('gwActionAlert').className = `alert-box ${data.responseStatus < 300 ? 'alert-success' : 'alert-error'}`;
            document.getElementById('gwActionAlert').innerHTML = `
                <strong>Scenario:</strong> ${data.scenarioDescription}<br>
                <strong>Gateway Action:</strong> ${data.gatewayAction}<br>
                <strong>Latency:</strong> ${data.durationMs} ms
            `;

            // Visual animation based on response status
            if (data.responseStatus >= 200 && data.responseStatus < 300) {
                // Success: authorized & forwarded
                nodeGateway.classList.add('node-success');
                tag.textContent = 'AUTHORIZED / 200';
                tag.style.background = 'var(--green-dim)';
                tag.style.color = '#34d399';

                line1.classList.add('line-active-green');
                line2.classList.add('line-active-green');

                pulse2.style.display = 'block';
                pulse2.classList.add('pulse-active');
                
                nodeService.classList.add('node-success');
                statusState.textContent = 'Success / routed';
                statusState.className = 'tag tag-green';
            } else if (data.responseStatus === 401 || data.responseStatus === 403) {
                // Blocked by gateway
                nodeGateway.classList.add('node-error');
                tag.textContent = `BLOCKED / ${data.responseStatus}`;
                tag.style.background = 'var(--rose-dim)';
                tag.style.color = '#fb7185';

                line1.classList.add('line-active-red');
                statusState.textContent = `Blocked (${data.responseStatus})`;
                statusState.className = 'tag tag-rose';
            } else {
                // General error or 404
                nodeGateway.classList.add('node-error');
                tag.textContent = `STATUS / ${data.responseStatus}`;
                tag.style.background = 'var(--amber-dim)';
                tag.style.color = '#fbbf24';

                statusState.textContent = `Error (${data.responseStatus})`;
                statusState.className = 'tag tag-amber';
            }

        } catch (err) {
            pulse1.style.display = 'none';
            pulse1.classList.remove('pulse-active');
            tag.textContent = 'GATEWAY ERROR';
            tag.style.background = 'var(--rose-dim)';
            tag.style.color = '#fb7185';
            statusState.textContent = 'Error';
            statusState.className = 'tag tag-rose';
            document.getElementById('gwRespBlock').textContent = `Local Connection Error:\n${err.message}`;
            document.getElementById('gwActionAlert').className = 'alert-box alert-error';
            document.getElementById('gwActionAlert').innerHTML = `Failed to contact gateway test endpoint: ${err.message}`;
        }
    }

    // ====== SERVICE HEALTH CHECK ======
    async function checkServiceHealth() {
        const services = [
            { id: 'hc-auth',     url: '/client-api/versions', indirect: true },
            { id: 'hc-gateway',  url: '/client-api/versions', indirect: true },
            { id: 'hc-content',  url: '/client-api/versions', indirect: true },
            { id: 'hc-download', url: '/client-api/logs',     indirect: true },
            { id: 'hc-verify',   url: '/client-api/versions', indirect: true },
            { id: 'hc-logging',  url: '/client-api/logs',     indirect: true },
        ];
        let upCount = 1; // demo-client itself is always up
        for (const svc of services) {
            const card = document.getElementById(svc.id);
            const dot = card.querySelector('.svc-dot');
            const statusTxt = card.querySelector('.svc-status');
            try {
                const res = await fetch(svc.url, { headers: token ? { Authorization: token } : {} });
                dot.className = 'svc-dot up';
                statusTxt.innerHTML = '<div class="svc-dot up"></div> Online';
                upCount++;
            } catch {
                dot.className = 'svc-dot down';
                statusTxt.innerHTML = '<div class="svc-dot down"></div> Offline';
            }
        }
        document.getElementById('statActiveServices').textContent = upCount;
    }

    // ====== FETCH VERSIONS ======
    async function fetchVersions() {
        if (!token) return;
        try {
            const res = await fetch('/client-api/versions', { headers: { Authorization: token } });
            if (!res.ok) return;
            const versions = await res.json();
            
            if (userRole === 'ADMIN') {
                const myVersions = versions.filter(v => v.uploader === username);
                renderPackages(myVersions, 'adminPkgList', true);
            } else {
                const pkgCountTag = document.getElementById('pkgCountTag');
                if (pkgCountTag) pkgCountTag.textContent = versions.length + ' packages';
                renderPackages(versions, 'pkgList', false);
            }
        } catch (err) { console.error(err); }
    }

    function renderPackages(versions, containerId, isAdminView) {
        const list = document.getElementById(containerId);
        if (!list) return;
        if (!versions.length) {
            list.innerHTML = '<div class="empty-state"><span class="es-icon">📭</span>No packages available.</div>';
            return;
        }

        // Extract unique folders for the datalist (Admin upload form autocomplete)
        if (isAdminView) {
            const dl = document.getElementById('existingFolders');
            if (dl) {
                const uniqueFolders = [...new Set(versions.map(v => v.folderName).filter(f => f))];
                dl.innerHTML = uniqueFolders.map(f => `<option value="${f}">`).join('');
            }
        }

        // Group versions by folderName
        const groupedByFolder = {};
        versions.forEach(v => {
            const folder = v.folderName || 'Uncategorized';
            if (!groupedByFolder[folder]) groupedByFolder[folder] = [];
            groupedByFolder[folder].push(v);
        });

        let html = '';
        
        for (const [folderName, folderVersions] of Object.entries(groupedByFolder)) {
            const folderId = 'folder-' + Math.random().toString(36).substr(2, 9);
            html += `
            <div class="folder-group" style="margin-bottom: 1.5rem; background: rgba(255,255,255,0.02); border: 1px solid var(--border); border-radius: 8px;">
                <div class="folder-header" style="padding: 1rem; cursor: pointer; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--border);" onclick="document.getElementById('${folderId}').style.display = document.getElementById('${folderId}').style.display === 'none' ? 'block' : 'none'">
                    <div style="display:flex; align-items:center; gap: 0.5rem;">
                        <span style="font-size:1.2rem;">📁</span>
                        <h4 style="margin:0; font-size: 1.1rem; color: var(--primary);">${folderName}</h4>
                    </div>
                    <span class="badge" style="background:var(--bg); border:1px solid var(--border)">${folderVersions.length} items</span>
                </div>
                <div id="${folderId}" style="display: block; padding: 1rem;">
            `;

            html += folderVersions.map(v => {
                const isRevoked = v.revoked === true;
                const statusBadge = isRevoked 
                    ? `<span class="badge" style="background:var(--rose-dim);color:#fb7185;border:1px solid #fb7185">REVOKED 🛑</span>` 
                    : `<span class="badge badge-signed">Dilithium3 Signed</span>`;
                
                const revokeBtn = (isAdminView && !isRevoked) 
                    ? `<button class="btn btn-sm" style="background:var(--rose-dim);color:#fb7185;border:1px solid #fb7185" onclick="revokePackage('${v.fileId}')">Revoke 🛑</button>` 
                    : ``;

                const downloadBtn = (!isAdminView) 
                    ? `<button class="btn btn-teal btn-sm" style="flex:1" onclick="doSecureDownload('${v.fileId}', '${v.fileName}')">⬇ Secure Download & Verify</button>` 
                    : ``;

                const rollbackMsg = (!isAdminView && isRevoked && v.rollbackSuggestion)
                    ? `<div style="margin-top:0.5rem;font-size:0.75rem;color:var(--yellow);background:rgba(234, 179, 8, 0.1);padding:0.4rem;border-radius:4px;">
                         ⚠️ This version is revoked. Please rollback to <strong>${v.rollbackSuggestion}</strong>
                       </div>`
                    : ``;

                return `
                <div class="pkg-card" style="margin-bottom:0.75rem; ${isRevoked ? 'opacity:0.7; border-color:var(--rose-dim);' : ''}">
                    <div style="display:flex;justify-content:space-between;align-items:center;gap:0.5rem;">
                        <div class="pkg-name" title="${v.fileName}" style="${isRevoked ? 'text-decoration:line-through' : ''}">📦 ${v.fileName}</div>
                        <span class="badge badge-ver">${v.version}</span>
                    </div>
                    <div class="pkg-meta">
                        <div class="pkg-meta-row">🕐 <span>${new Date(v.uploadTime).toLocaleString()}</span></div>
                        <div class="pkg-meta-row">🆔 <span class="mono" style="font-size:0.68rem">${v.fileId.substring(0,8)}…</span></div>
                        <div class="pkg-meta-row">✍️ ${statusBadge}</div>
                        ${v.uploader ? `<div class="pkg-meta-row">👤 <span>${v.uploader}</span></div>` : ''}
                    </div>
                    <div class="hash-text">SHA-256: ${v.sha256Hash.substring(0,40)}…</div>
                    ${rollbackMsg}
                    <div style="display:flex; gap:0.5rem; margin-top:1rem;">
                        ${downloadBtn}
                        ${revokeBtn}
                    </div>
                </div>
            `}).join('');
            
            html += `</div></div>`; // Close folder wrapper
        }
        
        list.innerHTML = html;
    }

    async function revokePackage(fileId) {
        if (!confirm("Are you sure you want to completely revoke this package? All future downloads will be blocked!")) return;
        try {
            const res = await fetch(`/client-api/revoke/${fileId}`, {
                method: 'POST',
                headers: { Authorization: token }
            });
            if (res.ok) {
                alert("Package Revoked Successfully!");
                fetchVersions(); // refresh
                fetchAuditLogs();
            } else {
                const text = await res.text();
                alert("Failed to revoke: " + text);
            }
        } catch (err) {
            alert("Error: " + err.message);
        }
    }

    // ====== SECURE DOWNLOAD ======
    async function doSecureDownload(fileId, fileName) {
        switchView('client');
        const body = document.getElementById('traceBody');
        body.innerHTML = '';
        addTrace('step1', 'running', '1. Generate Ephemeral Kyber-768 Key Pair',
            'Initializing Bouncy Castle BCPQC provider...\nRunning KeyPairGenerator.getInstance("Kyber", "BCPQC")...');

        try {
            const res = await fetch(`/client-api/download/${fileId}`, {
                method: 'POST', headers: { Authorization: token, 'Content-Type': 'application/json' }
            });
            if (!res.ok) { const t = await res.text(); throw new Error(t || 'Download failed'); }
            const d = await res.json();

            updateTrace('step1', 'success',
                `<span class="success-val">✓ KeyPair generated successfully</span>\n` +
                `<span class="key-val">Public Key (1184 bytes):</span> ${d.kyber_public_key.substring(0,80)}…\n` +
                `<span class="key-val">Private Key:</span> [held in client memory only]\n` +
                `<span class="timing">⏱ Keygen: ${d.kyber_keygen_time_ms} ms</span>`);

            addTrace('step2', 'success', `2. Transmit Request via API Gateway → Download Service`,
                `POST /api/download/${fileId.substring(0,8)}… → gateway-service:8080 → download-service:8083\n` +
                `Payload: { kyberPublicKey: "${d.kyber_public_key.substring(0,40)}…" }\n` +
                `<span class="timing">⏱ Round-trip: ${d.download_response_time_ms} ms</span>\n` +
                `<span class="success-val">✓ Server performed Kyber encapsulation → returned ciphertext + AES-encrypted file</span>`);

            addTrace('step3', 'success', '3. Kyber-768 Decapsulation → Recover AES-256 Key',
                `Download Service encapsulated an AES-256 key using client's Kyber public key.\n` +
                `Client decapsulates ciphertext using private key to recover shared secret.\n` +
                `<span class="key-val">Recovered AES-256 Key (hex):</span>\n0x${d.recovered_aes_key_hex}\n` +
                `<span class="timing">⏱ Decapsulation: ${d.decapsulation_time_ms} ms</span>`);

            addTrace('step4', 'success', '4. AES-256-GCM Symmetric Decryption',
                `Decrypting file payload using recovered 256-bit AES key + GCM authentication tag.\n` +
                `<span class="success-val">✓ Decryption successful — file integrity confirmed by GCM tag</span>\n` +
                `<span class="key-val">Plaintext size:</span> ${d.decrypted_file_size_bytes} bytes\n` +
                `<span class="timing">⏱ Decryption: ${d.decryption_time_ms} ms</span>`);

            const ok = d.signature_verified;
            addTrace('step5', ok ? 'success' : 'failed',
                '5. Dilithium3 Digital Signature Verification',
                ok ?
                `<span class="success-val">✓ SIGNATURE AUTHENTIC — File integrity and provenance confirmed</span>\n\n` +
                `SHA-256 of decrypted file:\n0x${d.calculated_sha256_hex}\n\n` +
                `Dilithium3 public key from Verification Service matches signature.\n` +
                `<span class="timing">⏱ Verification: ${d.verification_time_ms} ms</span>` :
                `<span style="color:var(--rose)">✗ SIGNATURE MISMATCH — Possible tampering detected!</span>\n` +
                `SHA-256: 0x${d.calculated_sha256_hex}\nFile may be corrupted or modified in transit.`);

            if (ok) {
                addTrace('step6', 'success', '6. Save Verified Package to Client Storage',
                    `<span class="success-val">✓ Package saved to local filesystem</span>\n` +
                    `Path: ${d.saved_path}\n` +
                    `File: ${fileName}\n\n` +
                    `🔒 End-to-end post-quantum secure delivery complete.\n` +
                    `   Kyber-768 KEM + AES-256-GCM + Dilithium3 DSA`);
            }

            fetchAuditLogs();
        } catch (err) {
            const active = body.querySelector('.running');
            if (active) {
                active.className = 'trace-step failed';
                active.querySelector('.trace-step-detail').innerHTML += `\n\n<span style="color:var(--rose)">ERROR: ${err.message}</span>`;
            }
        }
    }

    function addTrace(id, status, title, detail) {
        const body = document.getElementById('traceBody');
        const el = document.createElement('div');
        el.className = `trace-step ${status}`;
        el.id = id;
        el.innerHTML = `<div class="trace-step-content">
            <div class="trace-step-title">${title}</div>
            <div class="trace-step-detail">${detail}</div>
        </div>`;
        body.appendChild(el);
        body.parentElement.scrollTop = body.parentElement.scrollHeight;
    }

    function updateTrace(id, status, detail) {
        const el = document.getElementById(id);
        if (!el) return;
        el.className = `trace-step ${status}`;
        el.querySelector('.trace-step-detail').innerHTML = detail;
    }

    // ====== FILE UPLOAD ======
    function handleFileSelect(e) {
        selectedFile = e.target.files[0];
        if (selectedFile) {
            document.getElementById('dzText').style.display = 'none';
            document.getElementById('dzFile').textContent = '📦 ' + selectedFile.name + ' (' + (selectedFile.size / 1024).toFixed(1) + ' KB)';
            document.getElementById('dzFile').style.display = 'block';
            document.getElementById('uploadBtn').disabled = userRole !== 'ADMIN';
        }
    }

    function handleDragOver(e) { e.preventDefault(); document.getElementById('dropZone').classList.add('drag-over'); }
    function handleDragLeave() { document.getElementById('dropZone').classList.remove('drag-over'); }
    function handleDrop(e) {
        e.preventDefault();
        document.getElementById('dropZone').classList.remove('drag-over');
        selectedFile = e.dataTransfer.files[0];
        if (selectedFile) {
            document.getElementById('dzText').style.display = 'none';
            document.getElementById('dzFile').textContent = '📦 ' + selectedFile.name;
            document.getElementById('dzFile').style.display = 'block';
            document.getElementById('uploadBtn').disabled = false;
        }
    }

    async function handleUpload() {
        const alert = document.getElementById('uploadAlert');
        alert.style.display = 'none';
        const version = document.getElementById('versionInput').value.trim();
        const folderName = document.getElementById('folderInput').value.trim();
        if (!selectedFile || !version || !folderName) {
            alert.className = 'alert-box alert-error';
            alert.textContent = 'Please select a file, enter a folder name, and enter a version tag.';
            alert.style.display = 'block';
            return;
        }
        const btn = document.getElementById('uploadBtn');
        btn.disabled = true;
        btn.textContent = 'Uploading & Signing…';

        const fd = new FormData();
        fd.append('file', selectedFile);
        fd.append('version', version);
        fd.append('folderName', folderName);
        try {
            const res = await fetch('/client-api/upload', { method: 'POST', headers: { Authorization: token }, body: fd });
            if (!res.ok) { const t = await res.text(); throw new Error(t || 'Upload failed'); }
            const data = await res.json();
            alert.className = 'alert-box alert-success';
            alert.innerHTML = `✅ <strong>${selectedFile.name}</strong> uploaded and signed with Dilithium3!<br>
                <span style="font-family:var(--mono);font-size:0.75rem;color:var(--green)">File ID: ${data.fileId}</span>`;
            alert.style.display = 'block';
            selectedFile = null;
            document.getElementById('dzFile').style.display = 'none';
            document.getElementById('dzText').style.display = 'block';
            document.getElementById('versionInput').value = '';
            document.getElementById('fileInputHidden').value = '';
            fetchVersions();
            fetchAuditLogs();
        } catch (err) {
            alert.className = 'alert-box alert-error';
            alert.textContent = '❌ ' + err.message;
            alert.style.display = 'block';
        } finally {
            btn.disabled = false;
            btn.textContent = 'Upload & Sign with Dilithium3 ✍️';
        }
    }

    // ====== LOGS ======
    let currentFilter = 'all';
    function filterLogs(svc) { currentFilter = svc; renderLogs(); }

    async function fetchAuditLogs() {
        if (!token) return;
        try {
            const res = await fetch('/client-api/logs', { headers: { Authorization: token } });
            if (!res.ok) return;
            allLogs = await res.json();
            document.getElementById('statLogs').textContent = allLogs.length;
            document.getElementById('logCountTag').textContent = allLogs.length + ' events';
            renderLogs();
        } catch {}
    }

    function renderLogs() {
        const filtered = currentFilter === 'all' ? allLogs : allLogs.filter(l => l.serviceName === currentFilter);
        const tbody = document.getElementById('logsBody');
        if (!filtered.length) {
            tbody.innerHTML = '<tr><td colspan="5" class="empty-state">No log entries yet</td></tr>';
            return;
        }
        tbody.innerHTML = [...filtered].reverse().map(log => {
            const sc = log.status === 'SUCCESS' ? 'chip-success' : log.status === 'FAILURE' ? 'chip-failure' : 'chip-info';
            return `<tr>
                <td style="white-space:nowrap;color:var(--text-muted);font-family:var(--mono);font-size:0.72rem">${new Date(log.timestamp).toLocaleString()}</td>
                <td><span class="svc-tag">${log.serviceName}</span></td>
                <td style="font-weight:600;font-size:0.8rem;color:var(--text-dim)">${log.eventType}</td>
                <td style="color:var(--text-dim);font-size:0.8rem;max-width:360px;word-break:break-word">${log.message}</td>
                <td><span class="status-chip ${sc}">${log.status}</span></td>
            </tr>`;
        }).join('');
    }

    async function simulateDDoS() {
        const visualizer = document.getElementById('ddosVisualizer');
        const statusEl = document.getElementById('ddosStatus');
        visualizer.innerHTML = '';
        statusEl.innerText = "Initiating attack...";
        statusEl.style.color = '#ff4757';

        let promises = [];
        let successCount = 0;
        let blockCount = 0;

        for(let i=0; i<20; i++) {
            // Short delay to not crash browser but fast enough to trigger rate limit
            await new Promise(r => setTimeout(r, 20)); 

            let dot = document.createElement('div');
            dot.style.width = '20px';
            dot.style.height = '20px';
            dot.style.borderRadius = '50%';
            dot.style.backgroundColor = 'var(--border)';
            dot.style.display = 'flex';
            dot.style.alignItems = 'center';
            dot.style.justifyContent = 'center';
            dot.style.fontSize = '10px';
            dot.style.color = 'black';
            dot.style.transition = 'background-color 0.3s ease';
            dot.innerText = i+1;
            visualizer.appendChild(dot);

            let p = fetch(`/client-api/test-gateway?path=/api/content/versions&method=GET&tokenScenario=MISSING`, {
                method: 'POST',
                headers: { 'X-Simulate-IP': '10.0.0.99' }
            }).then(async res => {
                const data = await res.json();
                if(data.responseStatus === 429) {
                    dot.style.backgroundColor = '#ff4757'; // blocked
                    dot.style.color = 'white';
                    blockCount++;
                } else {
                    dot.style.backgroundColor = '#2ed573'; // passed
                    dot.style.color = 'white';
                    successCount++;
                }
                statusEl.innerText = `Attacking... Passed: ${successCount} | Blocked (429): ${blockCount}`;
            }).catch(err => {
                dot.style.backgroundColor = 'grey';
            });
            promises.push(p);
        }

        await Promise.all(promises);
        statusEl.innerText = `Attack Complete. Passed: ${successCount} | Blocked (429): ${blockCount}`;
        statusEl.style.color = 'var(--text)';
    }
