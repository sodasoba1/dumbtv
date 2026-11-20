#!/usr/bin/env python3
"""A pretend Fire TV for the scripts: answers adb commands from a JSON state file
and logs every shell command, so tests can see exactly what a script would do.

Like a real TV on Wi-Fi, stopping the DNS filter's VPN breaks the adb session
until the host disconnects and connects again."""
import json
import os
import sys
from pathlib import Path

VPN_APP = 'dnsfilter.android'

args = sys.argv[1:]
state_path = Path(os.environ['FAKE_TV_STATE'])
state = json.loads(state_path.read_text())


def save():
    state_path.write_text(json.dumps(state))


if args == ['start-server'] or args[:1] == ['connect']:
    sys.exit(0)
if args[-1:] == ['get-state']:
    print('device')
    sys.exit(0)
if len(args) >= 5 and args[0] == '-s' and args[2] == 'pull':
    Path(args[4]).write_bytes(b'fake installed apk')
    print(f'{args[3]}: 1 file pulled')
    sys.exit(0)
if len(args) >= 4 and args[0] == '-s' and args[2] == 'install':
    with Path(os.environ['FAKE_TV_LOG']).open('a') as log:
        log.write('install ' + ' '.join(args[3:]) + '\n')
    print('Success')
    sys.exit(0)
if args[:1] == ['disconnect']:
    state['dropped'] = False
    save()
    sys.exit(0)
if args[-1:] == ['get-state']:
    print('device')
    sys.exit(0)
if args[-1:] == ['get-serialno']:
    print(args[1] if len(args) >= 3 and args[0] == '-s' else 'test-tv')
    sys.exit(0)
if len(args) == 5 and args[0] == '-s' and args[2] == 'push' and 'pushed' in state:
    # Keeps a copy of each pushed file, so a test can read what reached the TV.
    Path(state['pushed']).mkdir(parents=True, exist_ok=True)
    Path(state['pushed'], Path(args[4]).name).write_bytes(Path(args[3]).read_bytes())
    print(f'{args[3]}: 1 file pushed')
    sys.exit(0)
if len(args) < 3 or args[0] != '-s' or args[2] != 'shell':
    sys.exit(91)

command = ' '.join(args[3:])
if state.get('dropped'):
    print('error: closed')
    sys.exit(1)
with Path(os.environ['FAKE_TV_LOG']).open('a') as log:
    log.write(command + '\n')

apps = state['packages']
settings = state.setdefault('settings', {})
words = command.split()

if words[:2] == ['content', 'insert'] and '--uri' in words:
    # Model the Settings provider fallback used when Fire OS ignores settings put.
    uri = words[words.index('--uri') + 1]
    namespace = uri.rsplit('/', 1)[-1]
    bindings = {}
    for index, word in enumerate(words[:-1]):
        if word == '--bind':
            binding_name, separator, remainder = words[index + 1].partition(':')
            if separator:
                value_type, separator, value = remainder.partition(':')
                if separator and value_type == 's':
                    bindings[binding_name] = value
    if namespace in ('secure', 'system', 'global') and 'name' in bindings and 'value' in bindings:
        settings[f'{namespace}/{bindings["name"]}'] = bindings['value']
        save()
        print('Inserted 1 row')
    else:
        state.setdefault('bad', []).append(command)
        save()
        print('Error: unsupported content insert')
        sys.exit(1)
elif words[:1] == ['settings']:
    verb, rest = words[1], words[2:]
    if verb == 'list' and len(rest) == 1:
        prefix = rest[0] + '/'
        for key, value in sorted(settings.items()):
            if key.startswith(prefix):
                print(key[len(prefix):] + '=' + value)
    elif verb == 'put' and len(rest) == 3:
        if state.get('settings_write_refused'):
            print('Error: settings write refused')
            sys.exit(1)
        if not state.get('settings_put_noop'):
            settings[f'{rest[0]}/{rest[1]}'] = '' if rest[2] == '""' else rest[2]
            save()
    elif verb == 'put':
        state.setdefault('bad', []).append(command)
        save()
        print('Bad arguments')
    elif verb == 'get' and len(rest) == 2:
        print(settings.get(f'{rest[0]}/{rest[1]}', 'null'))
    elif verb == 'delete' and len(rest) == 2:
        settings.pop(f'{rest[0]}/{rest[1]}', None)
        save()
elif words[:1] == ['device_config']:
    verb, rest = (words[1], words[2:]) if len(words) > 1 else ('', [])
    key = '/'.join(['config'] + rest[:2])
    if verb == 'get' and len(rest) == 2:
        print(settings.get(key, 'null'))
    elif verb in ('put', 'delete') and len(rest) == (3 if verb == 'put' else 2):
        if state.get('device_config_refused'):
            print('Exception occurred while executing: Permission denial')
            sys.exit(255)
        if verb == 'put':
            settings[key] = rest[2]
        else:
            settings.pop(key, None)
        save()
    else:
        state.setdefault('bad', []).append(command)
        save()
        print('Bad arguments')
elif command == 'echo ok':
    print('ok')
elif command == 'cat /proc/sys/kernel/random/boot_id':
    print(f"boot-{state.get('boots', 0)}")
elif command == 'svc power reboot' and state.get('svc_refused'):
    print('Killed')
elif command == 'getprop sys.shutdown.requested':
    print(state.pop('shutting_down', ''))
    save()
elif command in ('svc power reboot', 'reboot'):
    if command == 'svc power reboot':
        state['shutting_down'] = '1reboot'
        save()
    if not state.get('ignores_reboot'):
        state['boots'] = state.get('boots', 0) + 1
        save()
elif command == 'getprop ro.build.version.sdk':
    print(state.get('sdk', 25))
elif command == 'getprop ro.product.manufacturer':
    print(state.get('manufacturer', 'Amazon'))
elif command == 'getprop sys.boot_completed':
    print(1)
elif words[:1] == ['getprop'] and len(words) == 2 and words[1] in state.get('props', {}):
    print(state['props'][words[1]])
elif command == 'ip route show table all' and 'routes' in state:
    print(state['routes'])
elif command == 'cmd package help':
    print('Package manager (package) commands:\n  path [--user USER_ID] PACKAGE')
elif words[:2] == ['pm', 'path']:
    if words[2] in apps:
        print(f'package:/system/app/{words[2]}.apk')
elif command == 'pm list packages':
    print('\n'.join(f'package:{p}' for p in apps))
elif command == 'pm list packages -s':
    print('\n'.join(f'package:{p}' for p, a in apps.items() if a['system']))
elif command == 'pm list packages -d':
    omitted = state.get('omit_disabled_package', '')
    print('\n'.join(f'package:{p}' for p, a in apps.items()
                      if not a['enabled'] and p != omitted))
elif words[:4] == ['pm', 'disable-user', '--user', '0']:
    name = words[4]
    if apps.get(name, {}).get('protected'):
        print('Security exception: Cannot disable a protected package')
    else:
        apps[name]['enabled'] = False
        apps[name]['enabled_state'] = 3
        if name == VPN_APP:
            state['dropped'] = True
        save()
        print(f'Package {name} new state: disabled-user')
elif words[:4] == ['pm', 'enable', '--user', '0']:
    if words[4] in apps:
        apps[words[4]]['enabled'] = True
        apps[words[4]]['enabled_state'] = 0
        save()
        print(f'Package {words[4]} new state: enabled')
elif words[:4] == ['pm', 'uninstall', '--user', '0']:
    apps.pop(words[-1], None)
    save()
    print('Success')
elif words[:2] == ['dumpsys', 'package']:
    print('    userId=10050')
    if len(words) > 2 and words[2] in apps:
        app = apps[words[2]]
        state_id = app.get('enabled_state', 0 if app.get('enabled', True) else (2 if app.get('system_disabled') else 3))
        print(f'  User 0: installed=true enabled={state_id}')
    if words[2:] and words[2] in state.get('versions', {}):
        print(f"    versionCode={state['versions'][words[2]]} minSdk=21 targetSdk=25")
    for permission, granted in state.get('permissions', {}).get(words[2] if len(words) > 2 else '', {}).items():
        print(f"    {permission}: granted={str(granted).lower()}")
elif command == 'ps -A -o NAME':
    print('NAME')
elif command == 'dumpsys activity activities':
    print(state.get('foreground_line',
                    '  mResumedActivity: ActivityRecord{1 u0 tv.dumbtv.home/.Home t1}'))
elif words[:2] == ['am', 'force-stop']:
    if state.get('force_stop_refused'):
        print('Error: force-stop refused')
        sys.exit(1)
    if len(words) > 2 and words[2] == 'tv.dumbtv.home':
        state['homesentinel_running'] = False
    save()
elif words[:2] == ['am', 'start']:
    if state.get('home_launch_refused'):
        print('Error: HOME launch refused')
        sys.exit(1)
    if '-p' in words and words[words.index('-p') + 1] == 'com.amazon.tv.launcher':
        if not state.get('wrong_home_foreground'):
            state['foreground_line'] = (
                '  mResumedActivity: ActivityRecord{2 u0 '
                'com.amazon.tv.launcher/com.amazon.tv.launcher.ui.HomeActivity_vNext t1}')
    save()
elif words[:2] == ['appops', 'set'] and len(words) >= 5:
    package, operation, mode = words[2], words[3], words[4]
    state.setdefault('appops', {}).setdefault(package, {})[operation] = mode
    save()
elif words[:2] == ['appops', 'get'] and len(words) >= 4:
    package, operation = words[2], words[3]
    value = state.get('appops', {}).get(package, {}).get(operation, 'default')
    print(f'{operation}: {value}')
elif words[:2] == ['appops', 'write-settings']:
    pass
elif words[:1] == ['appops']:
    pass
elif words[:2] == ['dumpsys', 'deviceidle']:
    # The fake tracks command ordering; Doze whitelist state is not queried here.
    pass
elif words[:3] == ['dumpsys', 'activity', 'services']:
    if state.get('homesentinel_running', True):
        print('ServiceRecord tv.dumbtv.home/.HomeSentinel isForeground=true')
elif words[:2] == ['dumpsys', 'window']:
    print('mCurrentFocus=Window{1 u0 tv.dumbtv.home/.Home}')
elif words[:2] == ['input', 'keyevent']:
    pass
elif words[:2] == ['cmd', 'netpolicy']:
    action = words[2] if len(words) > 2 else ''
    if action == 'get' and words[3:] == ['restrict-background']:
        print(state.get('restrict_background', 'disabled'))
    elif action == 'set' and words[3:5] == ['restrict-background', 'true']:
        state['restrict_background'] = 'enabled'; save()
    elif action == 'set' and words[3:5] == ['restrict-background', 'false']:
        state['restrict_background'] = 'disabled'; save()
    elif action == 'set' and words[3:5] == ['restrict-background', 'enabled']:
        state['restrict_background'] = 'enabled'; save()
    elif action == 'set' and words[3:5] == ['restrict-background', 'disabled']:
        state['restrict_background'] = 'disabled'; save()
    elif action == 'list' and words[3:] == ['restrict-background-blacklist']:
        print(' '.join(str(uid) for uid in state.get('network_blacklist', [])))
    elif action == 'add' and words[3:4] == ['restrict-background-blacklist']:
        uid = int(words[4])
        values = state.setdefault('network_blacklist', [])
        if uid not in values: values.append(uid)
        save()
    elif action == 'remove' and words[3:4] == ['restrict-background-blacklist']:
        uid = int(words[4])
        state['network_blacklist'] = [value for value in state.get('network_blacklist', []) if value != uid]
        save()
elif words[:2] == ['cmd', 'package']:
    pass
elif words[:2] == ['pm', 'grant'] and len(words) >= 4:
    package, permission = words[2], words[3]
    if permission == 'android.permission.READ_LOGS' and state.get('read_logs_refused'):
        print('Operation not allowed: READ_LOGS grant refused')
        sys.exit(1)
    if not (permission == 'android.permission.READ_LOGS' and state.get('read_logs_not_recorded')):
        state.setdefault('permissions', {}).setdefault(package, {})[permission] = True
        save()
elif words[:2] == ['pm', 'revoke'] and len(words) >= 4:
    package, permission = words[2], words[3]
    state.setdefault('permissions', {}).setdefault(package, {})[permission] = False
    save()
