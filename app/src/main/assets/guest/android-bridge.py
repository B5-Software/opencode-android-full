"""APK-owned Linux helper. Talks only to the authenticated Android Unix socket."""
import json
import os
import socket
import sys

LIMIT = 512 * 1024


def main():
    command, *arguments = sys.argv[1:]
    if arguments == ['--help'] or not arguments and command not in ('android-import',):
        print('android-open URL | android-preview PORT | android-clipboard get/set [TEXT] | android-notify TITLE [TEXT] | android-share FILE | android-import [DIRECTORY] | android-export FILE')
        return 0
    if command == 'xdg-open' and len(arguments) == 1 and not arguments[0].lower().startswith(('http://', 'https://')):
        environment = os.environ.copy()
        environment.pop('BROWSER', None)
        environment['PATH'] = ':'.join(item for item in environment.get('PATH', '').split(':') if item != '/android-runtime/bin')
        os.execve('/usr/bin/xdg-open', ['/usr/bin/xdg-open', *arguments], environment)
    if command in ('android-open', 'xdg-open') and len(arguments) == 1:
        action, payload = 'open-url', {'url': arguments[0]}
    elif command == 'android-preview' and len(arguments) == 1:
        if not arguments[0].isascii() or not arguments[0].isdigit():
            raise ValueError('A numeric project port is required')
        port = int(arguments[0])
        if not 1024 <= port <= 65535 or port in (49374, 6080, 5901):
            raise ValueError('This is not a project development port')
        action, payload = 'preview', {'port': port}
    elif command == 'android-clipboard' and arguments == ['get']:
        action, payload = 'clipboard-get', {}
    elif command == 'android-clipboard' and arguments and arguments[0] == 'set' and len(arguments) <= 2:
        text = arguments[1] if len(arguments) == 2 else sys.stdin.read(65537)
        if len(text.encode('utf-8')) > 65536:
            raise ValueError('Clipboard text is too large')
        action, payload = 'clipboard-set', {'text': text}
    elif command == 'android-notify' and len(arguments) in (1, 2):
        action, payload = 'notify', {'title': arguments[0], 'text': arguments[1] if len(arguments) == 2 else ''}
    elif command in ('android-share', 'android-export') and len(arguments) == 1:
        action = 'share-file' if command == 'android-share' else 'file-export'
        payload = {'path': os.path.abspath(arguments[0])}
    elif command == 'android-import' and len(arguments) <= 1:
        action, payload = 'file-import', {'directory': os.path.abspath(arguments[0] if arguments else os.getcwd())}
    else:
        raise ValueError('Unsupported helper arguments')
    with open('/android-runtime/bridge-auth.json', encoding='utf-8') as stream:
        config = json.load(stream)
    request = json.dumps({'version': 1, 'token': config['token'], 'action': action, 'payload': payload}, ensure_ascii=False).encode('utf-8') + b'\n'
    if len(request) > LIMIT:
        raise ValueError('Request is too large')
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
        client.settimeout(620)
        client.connect('\0' + config['socket'])
        client.sendall(request)
        response = bytearray()
        while len(response) <= LIMIT:
            data = client.recv(min(8192, LIMIT + 1 - len(response)))
            if not data:
                raise ConnectionError('Android Bridge disconnected')
            response.extend(data)
            if b'\n' in response:
                break
        if len(response) > LIMIT:
            raise ValueError('Response is too large')
    result = json.loads(response.split(b'\n', 1)[0])
    if result.get('status') == 'cancelled':
        return 130
    if result.get('status') != 'ok':
        if action == 'preview' and result.get('error') == 'preview_not_listening':
            print('No local server is listening on that port. Start the project server before opening preview.', file=sys.stderr)
        else:
            print('Android system request was not completed: ' + result.get('error', 'unknown'), file=sys.stderr)
        return 1
    value = result.get('value', {})
    if action == 'preview':
        print(value.get('url', ''))
    elif action == 'clipboard-get':
        sys.stdout.write(value.get('text', ''))
    elif action == 'file-import':
        for path in value.get('paths', []):
            print(path)
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
    except Exception:
        print('Android Bridge unavailable or request rejected. Start the development environment and use a supported operation.', file=sys.stderr)
        sys.exit(1)
