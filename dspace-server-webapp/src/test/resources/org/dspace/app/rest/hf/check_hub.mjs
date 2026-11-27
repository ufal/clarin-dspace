// Drive the @huggingface/hub client against the DSpace HF facade.
//
// Usage: node check_hub.mjs <repoId> <expectedSize>
//
// Exits 0 when every .gguf listed by the repo passes both checks (declared download size,
// and real downloaded payload), 1 (with a diagnostic message on stdout) otherwise.

import { listFiles, fileDownloadInfo, downloadFile } from "@huggingface/hub";

const GGUF_MAGIC = [0x47, 0x47, 0x55, 0x46]; // ASCII "GGUF"

function fail(message) {
    console.log("FAIL: " + message);
    process.exit(1);
}

async function main() {
    const repoId = process.argv[2];
    const expectedSize = Number(process.argv[3]);
    const hubUrl = process.env.HUB_URL;

    if (!repoId || !Number.isFinite(expectedSize)) {
        fail("usage: check_hub.mjs <repoId> <expectedSize>");
    }

    const repo = { type: "model", name: repoId };

    const ggufPaths = [];
    for await (const f of listFiles({ repo, hubUrl })) {
        console.log("listFiles: " + f.path + " (" + f.size + " bytes)");
        if (f.path.endsWith(".gguf")) {
            ggufPaths.push(f.path);
        }
    }
    if (ggufPaths.length === 0) {
        fail("listFiles(" + repoId + ") returned no .gguf entries");
    }

    for (const path of ggufPaths) {
        const info = await fileDownloadInfo({ repo, path, hubUrl });
        if (info.size !== expectedSize) {
            fail("fileDownloadInfo(" + path + ").size = " + info.size + ", expected " + expectedSize);
        }
        console.log("fileDownloadInfo OK: " + path + " (" + info.size + " bytes, etag " + info.etag + ")");

        const blob = await downloadFile({ repo, path, hubUrl });
        if (blob == null) {
            fail("downloadFile(" + path + ") returned no blob");
        }
        const buf = new Uint8Array(await blob.arrayBuffer());
        if (buf.length !== expectedSize) {
            fail("downloadFile(" + path + ") produced " + buf.length + " bytes, expected " + expectedSize);
        }
        for (let i = 0; i < GGUF_MAGIC.length; i++) {
            if (buf[i] !== GGUF_MAGIC[i]) {
                fail("downloadFile(" + path + ") payload does not start with the GGUF magic: " +
                    Array.from(buf.slice(0, 4)).join(","));
            }
        }
        console.log("downloadFile OK: " + path + " (" + buf.length + " bytes, GGUF magic verified)");
    }

    console.log("OK");
    process.exit(0);
}

main().catch((err) => {
    fail("unexpected error: " + (err && err.stack ? err.stack : err));
});
