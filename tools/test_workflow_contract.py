"""Repository-format contracts; YAML syntax validation alone cannot validate Actions context scope."""
from pathlib import Path
import re
import unittest

ROOT=Path(__file__).resolve().parents[1]

def invalid_runner_contexts(text):
    errors=[]; env_indent=None
    for number,line in enumerate(text.splitlines(),1):
        stripped=line.lstrip();indent=len(line)-len(stripped)
        if not stripped or stripped.startswith("#"):continue
        if env_indent is not None and indent<=env_indent:env_indent=None
        # Workflows here use 0-space workflow env, 4-space job env and 8-space step env.
        if indent in (0,4) and re.fullmatch(r"env:\s*",stripped):env_indent=indent
        if env_indent is not None and re.search(r"\$\{\{\s*runner(?:\.|\[)",line):errors.append(number)
    return errors

class WorkflowContextContractTest(unittest.TestCase):
    def test_job_runner_context_fails_but_step_scope_is_legal(self):
        bad="jobs:\n  sign:\n    env:\n      GNUPGHOME: ${{ runner.temp }}/keys\n    steps: []\n"
        good="jobs:\n  sign:\n    steps:\n      - name: fixture\n        env:\n          GNUPGHOME: ${{ runner.temp }}/keys\n"
        self.assertEqual([4],invalid_runner_contexts(bad))
        self.assertEqual([],invalid_runner_contexts(good))
    def test_workflows_do_not_reference_runner_before_a_runner_exists(self):
        for path in (ROOT/".github/workflows").glob("*.yml"):
            self.assertEqual([],invalid_runner_contexts(path.read_text(encoding="utf-8")),path.name)
        release=(ROOT/".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertIn('GNUPGHOME=$RUNNER_TEMP/driverkit-gnupg',release)
        self.assertIn("if not configured: raise SystemExit(0)",release)

if __name__=="__main__":unittest.main()
