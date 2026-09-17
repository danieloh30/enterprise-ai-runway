const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

// Exercise the actual trusted inline workflow script without GitHub credentials.
const workflow = fs.readFileSync(path.join(__dirname, '../.github/workflows/dependabot-automerge.yml'), 'utf8');
const inline = workflow.split('          script: |\n')[1].split('      - name:')[0];
const script = inline.split('\n').map(line => line.slice(12)).join('\n');
const evaluate = new (Object.getPrototypeOf(async function () {}).constructor)('github', 'context', 'core', script);

function fixture() {
  const run = { id: 10, event: 'pull_request', conclusion: 'success', run_attempt: 1,
    path: '.github/workflows/verify.yml', head_sha: 'tested-sha', head_repository: { id: 1 } };
  return {
    context: { repo: { owner: 'danieloh30', repo: 'enterprise-ai-runway' },
      payload: { workflow_run: structuredClone(run), repository: { id: 1, default_branch: 'main' } } },
    run,
    candidates: [{ number: 42 }],
    pr: { number: 42, state: 'open', draft: false, user: { login: 'dependabot[bot]', type: 'Bot' },
      head: { repo: { id: 1 }, ref: 'dependabot/maven/quarkus-stack-123', sha: 'tested-sha' },
      base: { repo: { id: 1 }, ref: 'main' } },
    files: [{ status: 'modified', filename: 'pom.xml' }, { status: 'modified', filename: 'mcp-tools/pom.xml' }]
  };
}

async function outputs(f) {
  const out = {};
  const github = {
    rest: {
      actions: { getWorkflowRun: async () => ({ data: f.run }) },
      repos: { listPullRequestsAssociatedWithCommit: 'candidates' },
      pulls: { get: async () => ({ data: f.pr }), listFiles: 'files' }
    },
    paginate: async route => f[route]
  };
  await evaluate(github, f.context, { info: () => {}, setOutput: (key, value) => { out[key] = value; } });
  return out;
}

test('eligible grouped Maven upgrade returns only the tested PR and SHA', async () => {
  assert.deepEqual(await outputs(fixture()), { number: '42', 'head-sha': 'tested-sha' });
});

for (const [reason, change] of Object.entries({
  'failed verification': f => { f.run.conclusion = 'failure'; },
  'superseded run attempt': f => { f.run.run_attempt++; },
  'different workflow': f => { f.run.path = '.github/workflows/other.yml'; },
  'push instead of PR': f => { f.run.event = 'push'; },
  'changed event SHA': f => { f.context.payload.workflow_run.head_sha = 'other'; },
  'fork workflow run': f => { f.run.head_repository.id = 2; },
  'untested PR head': f => { f.pr.head.sha = 'new-untested-sha'; },
  'non-bot author': f => { f.pr.user = { login: 'someone', type: 'User' }; },
  'fork PR': f => { f.pr.head.repo.id = 2; },
  'different target repository': f => { f.pr.base.repo.id = 2; },
  'different target branch': f => { f.pr.base.ref = 'release'; },
  'non-Maven update': f => { f.pr.head.ref = 'dependabot/npm_and_yarn/example'; },
  'draft PR': f => { f.pr.draft = true; },
  'closed PR': f => { f.pr.state = 'closed'; },
  'workflow file modification': f => { f.files.push({ status: 'modified', filename: '.github/workflows/verify.yml' }); },
  'deleted POM': f => { f.files[0].status = 'removed'; },
  'empty change list': f => { f.files = []; },
  'no matching PR': f => { f.candidates = []; }
})) {
  test(`skips ${reason}`, async () => {
    const f = fixture();
    change(f);
    assert.deepEqual(await outputs(f), {});
  });
}
