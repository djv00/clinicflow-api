import { readdirSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';

const target = fileURLToPath(new URL('../target/', import.meta.url));
const jars = readdirSync(target).filter(name => name.endsWith('.jar'));
if (jars.length !== 1) throw new Error('Build the application first: mvnw -DskipTests package');
const java = process.env.JAVA_HOME
  ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
// Ignore developer deployment settings: browser tests own an in-memory database and local accounts.
const env = Object.fromEntries(Object.entries(process.env)
  .filter(([name]) => !/^(SPRING_|CLINICFLOW_|SERVER_|MANAGEMENT_)/i.test(name)));
const app = spawn(java, ['-jar', resolve(target, jars[0]),
  '--spring.config.location=classpath:/',
  '--spring.profiles.active=demo',
  '--server.address=127.0.0.1', '--server.port=18081',
  '--spring.datasource.url=jdbc:h2:mem:clinicflow_e2e;DB_CLOSE_ON_EXIT=FALSE',
  '--spring.security.user.name=operator', '--spring.security.user.password=e2e-operator-only',
  '--clinicflow.security.viewer.username=viewer', '--clinicflow.security.viewer.password=e2e-viewer-only',
  '--clinicflow.security.admin.username=admin', '--clinicflow.security.admin.password=e2e-admin-only'
], { env, stdio: 'inherit', windowsHide: true });
app.on('error', error => { console.error(error); process.exitCode = 1; });
app.on('exit', code => { process.exitCode = code ?? 1; });
process.on('SIGTERM', () => app.kill('SIGTERM'));
process.on('SIGINT', () => app.kill('SIGINT'));
