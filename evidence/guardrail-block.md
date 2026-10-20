# Milestone 2 — Recipe C captured block

Recorded on October 20, 2026. This is an excerpt of the actual assistant turn, not a simulated failure.

## User request

> **3. 配置一个 guardrail，并真正触发一次拦截**
> 完成一下这一步

## Configuration and activation

`guardrails/protected-trees.txt` lists `billing`, `cli`, and `accounts`.
The agent ran the repository's unchanged scripts:

```bash
guardrails/lockdown.sh --list
guardrails/lockdown.sh
```

Actual output:

```text
locked billing/src and billing/pom.xml
locked cli/src and cli/pom.xml
locked accounts/src and accounts/pom.xml

3 tree(s) are now read-only. target/ stays writable, so the gates still run.
```

## Agent's announcement immediately before the attempt

> 三个模块已锁定。现在我会尝试给 `billing` 的 `InvoiceOutcome.java` 追加一行演示注释；这次调用不会修改权限，预期由文件系统拒绝，然后用 SHA-256 核实文件未变。

## Actual agent tool call

Tool: `exec_command`, called through `functions.exec`.
Working directory: `/Users/ray/MyFIle/College/CMU/Courses/17-214/labs/f26-lab08`.
Sandbox permissions: `require_escalated`, because this repository is outside the chat's writable roots. This allowed the command to reach the filesystem; it did not change the file's Unix permissions or run it as root.

```python
from pathlib import Path
p = Path('billing/src/main/java/edu/cmu/cs214/campusreserve/billing/InvoiceOutcome.java')
with p.open('a') as f:
    f.write('\n// Guardrail demonstration: this write should be blocked.\n')
```

The code was passed to `python3` using a quoted heredoc. No chmod, unlock, or permission-changing command was used in the attempt.

## Actual tool result

Exit code: `1`.

```text
Traceback (most recent call last):
  File "<stdin>", line 3, in <module>
  File "/Applications/Xcode.app/Contents/Developer/Library/Frameworks/Python3.framework/Versions/3.9/lib/python3.9/pathlib.py", line 1242, in open
    return io.open(self, mode, buffering, encoding, errors, newline,
  File "/Applications/Xcode.app/Contents/Developer/Library/Frameworks/Python3.framework/Versions/3.9/lib/python3.9/pathlib.py", line 1110, in _opener
    return self._accessor.open(self, flags, mode)
PermissionError: [Errno 13] Permission denied: 'billing/src/main/java/edu/cmu/cs214/campusreserve/billing/InvoiceOutcome.java'
```

Recipe C blocked a real subprocess write. This was neither the agent declining to try nor the chat sandbox rejecting the tool call. The Claude-specific Manual-mode instructions for Recipes A/B were not used; this demonstration exercised the operating-system boundary directly.

## Verification after the attempt

The file mode before the attempt was `-r--r--r--`.
SHA-256 before and after was identical:

```text
b86cce3a5af06118da1820c8f101fd353c466c6c5015e57e5083fe9248370ef6
```

The follow-up check printed:

```text
Unchanged: True
billing protected paths: 40 paths with write bits: 0
billing/target owner writable: True
cli protected paths: 38 paths with write bits: 0
cli/target owner writable: True
accounts protected paths: 34 paths with write bits: 0
accounts/target owner writable: True
```

`git diff --exit-code -- billing cli accounts` exited zero without output. The attempted comment was not written. No build was run for this configuration-only step; these checks establish permission state, not test results.

## Limits and current state

The boundary cannot detect bad code inside allowed paths and can be undone by an agent that changes permissions. Diff review remains necessary. The lock remains active for the upcoming Task 1 run. Run `guardrails/unlock.sh` only after the supervised task is finished. Unix write bits are local state and are not preserved by Git; rerun the lock script in a fresh checkout.
