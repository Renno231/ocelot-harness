'use strict';
// Starts the harness's debug adapter (bin/ocelot-dap in a download, scripts/ocelot-dap in the
// source checkout) and relays VS Code's debugger messages to it. The adapter connects to the
// project's running ocelot-harnessd service.
const vscode = require('vscode');
const childProcess = require('child_process');
const fs = require('fs');
const path = require('path');

const WINDOWS = process.platform === 'win32';
const LAUNCHER = WINDOWS ? 'ocelot-dap.cmd' : 'ocelot-dap';

function findLauncher(home) {
  for (const folder of ['bin', 'scripts']) {
    const candidate = path.join(home, folder, LAUNCHER);
    if (fs.existsSync(candidate)) return candidate;
  }
  return undefined;
}

class HarnessAdapter {
  constructor(launcher) {
    this.emitter = new vscode.EventEmitter();
    this.onDidSendMessage = this.emitter.event;
    this.buffer = Buffer.alloc(0);
    this.sequence = 0;
    // .cmd files only start through a shell; quoting keeps folders with spaces intact.
    this.child = childProcess.spawn(WINDOWS ? `"${launcher}"` : launcher, [], {
      shell: WINDOWS,
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true
    });
    this.child.stdout.on('data', (chunk) => this.receive(chunk));
    this.child.stderr.on('data', (chunk) => this.event('output', { category: 'stderr', output: chunk.toString('utf8') }));
    this.child.on('error', (error) => this.event('output', { category: 'stderr', output: `cannot start ${launcher}: ${error.message}\n` }));
    this.child.on('exit', () => this.event('terminated', {}));
  }

  handleMessage(message) {
    const body = Buffer.from(JSON.stringify(message), 'utf8');
    this.child.stdin.write(`Content-Length: ${body.length}\r\n\r\n`);
    this.child.stdin.write(body);
  }

  receive(chunk) {
    this.buffer = Buffer.concat([this.buffer, chunk]);
    for (;;) {
      const end = this.buffer.indexOf('\r\n\r\n');
      if (end < 0) return;
      const match = /Content-Length:\s*(\d+)/i.exec(this.buffer.slice(0, end).toString('ascii'));
      const length = match ? Number(match[1]) : 0;
      if (this.buffer.length < end + 4 + length) return;
      const body = this.buffer.slice(end + 4, end + 4 + length).toString('utf8');
      this.buffer = this.buffer.slice(end + 4 + length);
      if (match) this.emitter.fire(JSON.parse(body));
    }
  }

  event(name, body) {
    this.emitter.fire({ seq: 0, type: 'event', event: name, body });
  }

  dispose() {
    if (this.child.exitCode === null) this.child.stdin.end();
  }
}

function activate(context) {
  context.subscriptions.push(
    vscode.debug.registerDebugAdapterDescriptorFactory('ocelot', {
      createDebugAdapterDescriptor(session) {
        const home = session.configuration.harness ||
          vscode.workspace.getConfiguration('ocelotHarness').get('home');
        const launcher = home && findLauncher(home);
        if (!launcher) {
          throw new Error('Set "ocelotHarness.home" (or "harness" in launch.json) to the Ocelot Harness folder that contains bin/ocelot-dap or scripts/ocelot-dap.');
        }
        return new vscode.DebugAdapterInlineImplementation(new HarnessAdapter(launcher));
      }
    })
  );
}

function deactivate() {}

module.exports = { activate, deactivate };
