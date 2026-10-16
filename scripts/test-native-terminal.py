#!/usr/bin/env python3
"""Qualify JLine's real terminal path, including the former GraalVM crash."""
import os
import pathlib
import pty
import select
import signal
import struct
import sys
import tempfile
import termios
import time
import fcntl

binary = pathlib.Path(sys.argv[1]).resolve()
with tempfile.TemporaryDirectory(prefix='fluxzero-native-terminal-') as workspace:
    directory = pathlib.Path(workspace)
    home = directory / 'home'
    home.mkdir()
    command = [str(binary), f'-Duser.home={home}']
    if binary.suffix == '.jar':
        java = pathlib.Path(os.environ['JAVA_HOME']) / 'bin/java'
        command = [str(java), f'-Duser.home={home}', '-jar', str(binary)]
    command += ['init', '--dir', workspace, '--name', 'pty-starter',
                '--package', 'com.example.terminal', '--group-id', 'com.example',
                '--artifact-id', 'pty-starter', '--description', 'Terminal qualification', '--build', 'maven']
    pid, terminal = pty.fork()
    if pid == 0:
        os.chdir(workspace)
        os.environ['TERM'] = 'xterm-256color'
        os.environ['FLUXZERO_CLI_AUTO_UPDATE'] = 'false'
        os.execv(command[0], command)
    fcntl.ioctl(terminal, termios.TIOCSWINSZ, struct.pack('HHHH', 24, 100, 0, 0))
    output = bytearray()
    selected = False
    status = None
    try:
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            ready, _, _ = select.select([terminal], [], [], 0.1)
            if ready:
                try:
                    chunk = os.read(terminal, 8192)
                except OSError:
                    chunk = b''
                output.extend(chunk)
                if not selected and b'Please select a template:' in output:
                    os.write(terminal, b'1\r')
                    selected = True
            completed, candidate = os.waitpid(pid, os.WNOHANG)
            if completed:
                status = candidate
                break
        assert status is not None, 'Interactive native CLI timed out'
        assert os.WIFEXITED(status) and os.WEXITSTATUS(status) == 0, f'Interactive CLI failed: {status}'
        assert selected, 'CLI must enter the real interactive template selector'
        assert (directory / 'pty-starter/pom.xml').is_file(), 'Selecting a template must create the project'
        assert b'Successfully generated your project' in output
        print('Interactive terminal qualified: real PTY, template selection and project generation.')
    except BaseException:
        print(output.decode(errors='replace')[-12000:], file=sys.stderr)
        raise
    finally:
        if status is None:
            try:
                os.killpg(pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            os.waitpid(pid, 0)
        os.close(terminal)
