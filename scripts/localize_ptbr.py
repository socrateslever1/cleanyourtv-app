#!/usr/bin/env python3
"""Gera recursos Android pt-BR dentro do APK do Clean Your TV.

Mantém as chaves, variáveis ICU, formatações Android e o código de segurança.
Tradução automatizada de textos públicos do projeto ocorre APENAS na compilação.
Se a tradução remota não estiver disponível, permanece o texto original;
o relatório registra rigorosamente quais entradas não foram localizadas.
"""
import argparse
import concurrent.futures
import html
import json
import re
import time
import threading
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from xml.sax.saxutils import escape

MODULES = ("app", "feature/connect", "feature/setup", "feature/manage",
           "feature/launcher", "feature/modules", "feature/backup", "ui/design")

# Vocabulário revisado manualmente para ações e avisos sensíveis.
# Nunca traduzir identificadores de pacotes, comandos ADB ou nomes de serviços.
FIXED = {
 "Back":"Voltar", "Cancel":"Cancelar", "OK":"OK", "Done":"Concluído",
 "Undo":"Desfazer", "Close":"Fechar", "Help":"Ajuda",
 "Start":"Começar", "Next":"Avançar", "Continue":"Continuar",
 "Connected":"Conectado", "Connecting...":"Conectando…",
 "Waiting":"Aguardando", "Working":"Em andamento", "Offline":"Desconectado",
 "Why?":"Por quê?", "Off":"Desativado", "Kept":"Preservado",
 "Keep it":"Manter ativado", "Switch it off":"Desativar",
 "Customise":"Personalizar", "Restore point":"Ponto de restauração",
 "Voice assistant":"Assistente de voz", "Chromecast":"Chromecast",
 "Software updates":"Atualizações de software",
 "Tracking and analytics":"Rastreamento e análise de uso",
 "Adverts and sponsored rows":"Anúncios e recomendações patrocinadas",
 "Apps nobody asked for":"Aplicativos pré-instalados dispensáveis",
 "Shop and demo mode":"Modo de demonstração de loja",
 "Home screen":"Tela inicial", "Your TV is ready":"Sua TV está pronta",
 "Nothing was changed":"Nenhuma alteração foi realizada",
 "Make it clean":"Aplicar limpeza", "Making it clean":"Aplicando limpeza",
 "Stop and undo":"Parar e desfazer", "Confirm it's you":"Confirme sua identidade",
 "Look at the TV":"Olhe para a TV", "Connected":"Conectado",
 "No TV found yet":"Nenhuma TV encontrada",
 "Looking for TVs on your Wi-Fi...":"Procurando TVs na sua rede Wi-Fi…",
 "Use the TV's own home screen":"Manter a tela inicial original da TV",
 "No restore point yet. One is saved before the first change.":"Ainda não existe ponto de restauração. Um será criado antes da primeira alteração.",
 "Your TV, tidied up.":"Sua TV, mais organizada.",
 "Put everything back":"Restaurar tudo", "Put back":"Reativar",
 "Nothing is deleted; \"off\" means asleep.":"Nenhum aplicativo é apagado; desativar significa interromper sua execução.",
}
BY_KEY = {
 "app_channel_setup_name":"Configurando uma TV",
 "app_channel_setup_description":"Mostra o andamento das alterações feitas na TV e avisa quando você precisa olhar para ela.",
 "app_restore_read_failed":"Não foi possível ler o ponto de restauração.",
 "welcome_title":"Sua TV, mais organizada.",
 "welcome_body":"Desative serviços desnecessários, mantenha os aplicativos que usa e simplifique a tela inicial. Todas as alterações podem ser revertidas.",
 "welcome_start":"Começar",
 "welcome_returning":"Já configurei uma TV antes",
 "picker_title":"Qual TV você quer configurar?",
 "picker_body":"Não tem certeza? A TV selecionada exibirá uma mensagem na tela em instantes.",
 "picker_footer":"O celular e a TV devem estar na mesma rede Wi-Fi.",
 "door_title":"Permitir acesso do celular",
 "door_body":"Pegue o controle remoto da TV. Esta é a única parte que exige alguns ajustes.",
 "door_menu_names_vary":"Os nomes dos menus podem variar conforme a versão da TV.",
 "door_same_wifi":"O celular e a TV devem estar na mesma rede Wi-Fi.",
 "door_still_waiting":"Ainda aguardando. A depuração USB está ativada? Algumas TVs também exigem depuração pela rede.",
 "door_connect_failed_title":"A TV respondeu, mas o celular não conseguiu estabelecer a conexão.",
 "door_connect_failed_hint":"Se o ADB funciona pelo computador, mas não pelo aplicativo, verifique as restrições de rede no celular, VPN, DNS privado e dados móveis. Desative essas restrições temporariamente e tente novamente.",
 "family_gtv_step1":"Na TV, acesse Configurações > Sistema > Sobre. Pressione OK em \"Versão do SO Android TV\" sete vezes, até ativar as opções do desenvolvedor.",
 "family_gtv_step2":"Volte uma tela. Acesse Sistema > Opções do desenvolvedor, ative a depuração USB e, se disponível, a depuração sem fio.",
 "family_gtv_step3":"Mantenha a TV nessa tela. O celular tentará se conectar automaticamente.",
 "allow_title":"Olhe para a TV",
 "allow_body":"A TV está perguntando se você confia neste celular. Marque \"Sempre permitir\" e escolha OK.",
 "allow_waiting":"Aguardando sua confirmação na TV…",
 "allow_still_waiting":"Ainda aguardando sua confirmação na TV.",
 "allow_trusted":"Autorizado",
 "allow_dont_see":"Não vejo a mensagem na TV",
 "stop_tv_not_found_title":"Nenhuma TV encontrada",
 "stop_tv_not_found_body":"Verifique se a TV está ligada e conectada à mesma rede Wi-Fi do celular. Pode levar alguns instantes para ela aparecer.",
 "stop_tv_not_found_primary":"Continuar procurando",
 "stop_tv_not_found_secondary":"Informar o endereço da TV",
 "gate_title":"Confirme sua identidade",
 "gate_negative":"Cancelar",
 "gate_failed":"Não foi possível confirmar sua identidade. Nenhuma alteração foi realizada.",
 "found_group_ads":"Anúncios e recomendações patrocinadas",
 "found_group_tracking":"Rastreamento e análise de uso",
 "found_group_nobody":"Aplicativos pré-instalados dispensáveis",
 "found_group_shop":"Modo de demonstração de loja",
 "found_group_needed":"Necessários para a nova tela inicial",
 "found_keep_intro":"Estes recursos serão preservados:",
 "found_keep_fixed":"TV ao vivo e HDMI, controle remoto, teclado, Chromecast, assistente de voz e atualizações de software",
 "found_safety":"Um ponto de restauração será salvo antes de qualquer alteração.",
 "found_make_clean":"Aplicar limpeza",
 "found_customise":"Personalizar",
 "found_home_casting":"Neste dispositivo Google, mudar a tela inicial exige desativar o assistente de configuração, o que pode interromper a transmissão do celular após a reinicialização. Por segurança, manteremos a tela inicial original e removeremos apenas itens dispensáveis.",
 "customise_nothing_deleted":"Nada será excluído. Desativar um aplicativo significa impedir temporariamente sua execução.",
 "customise_updates_note":"Manter ativado permite continuar recebendo correções de segurança. Desativar também remove avisos de atualização.",
 "customise_protected_title":"Itens protegidos",
 "customise_keep_debugging_warning":"Outros dispositivos na mesma rede Wi-Fi poderão solicitar acesso.",
 "working_step_restore_active":"Salvando ponto de restauração…",
 "working_step_restore_done":"Ponto de restauração salvo",
 "working_stop":"Parar e desfazer",
 "working_putting_back":"Restaurando a tela inicial original…",
 "working_stopping":"Interrompendo e desfazendo as alterações…",
 "done_title":"Sua TV está pronta",
 "done_title_nothing":"Nenhuma alteração foi realizada",
 "manage_title":"Clean Your TV",
 "manage_state_clean":"Otimizado",
 "manage_state_original":"Original",
 "manage_clean_again":"Executar limpeza novamente",
 "manage_row_home_own":"Tela inicial original preservada. Entenda o motivo.",
 "manage_row_switched_off_sub":"Você pode reativar qualquer item.",
 "manage_row_restore_sub":"{date}. Desfaça todas as alterações por aqui.",
 "switchedoff_intro":"Todos os aplicativos podem ser reativados. Nenhum deles foi excluído.",
 "switchedoff_put_back":"Reativar",
 "leftovers_title":"Verificar alterações após atualizações",
 "leftovers_none":"Nenhum item foi reativado. Tudo continua como você deixou.",
 "tvsettings_title":"Configurações da TV",
 "appsettings_title":"Configurações do aplicativo",
 "appsettings_check_updates":"Verificar atualizações",
 "appsettings_up_to_date":"Você já tem a versão mais recente",
 "restore_title":"Ponto de restauração",
 "restore_none":"Ainda não existe um ponto de restauração. Um será criado antes da primeira alteração.",
 "restore_row_undo":"Desfazer tudo",
 "restore_row_save":"Salvar uma cópia",
 "restore_row_factory":"Antes de restaurar os padrões de fábrica",
 "restore_preview_button":"Restaurar tudo",
 "restore_confirm_title":"Restaurar todas as alterações?",
 "restore_confirm_body":"Os anúncios e serviços de rastreamento também voltarão. Você poderá executar a limpeza novamente.",
 "restore_working_title":"Restaurando as configurações anteriores",
 "restore_done_title":"Restauração concluída",
 "restore_factory_button":"Reativar assistente de configuração",
 "restore_factory_working":"Reativando o assistente de configuração…",
 "restore_factory_done":"Assistente de configuração reativado. Você já pode restaurar os padrões de fábrica.",
 "restore_export_saved":"Cópia do ponto de restauração salva.",
 "restore_export_failed":"Não foi possível salvar a cópia. Escolha outro local.",
 "cyt_status_debugging_off":"Depuração desativada",
 "cyt_pill_off":"Desativado",
 "cyt_pill_kept":"Preservado",
 "cyt_action_keep":"Manter ativado",
 "cyt_action_switch_off":"Desativar",
 "cyt_state_doing":"Em andamento",
 "cyt_state_upcoming":"Próximo",
 "cyt_state_skipped":"Ignorado",
}

# Conserva interpolação nomeada, plurais ICU e argumentos do Android.
FORMAT = re.compile(r"\{[A-Za-z_][A-Za-z0-9_]*\}|%(?:[0-9]+\$)?[-+# 0,(]*[0-9]*(?:\.[0-9]+)?[a-zA-Z]|\\n|\\t")
TOKEN = re.compile(r"CZZKEEP[0-9]+ZZC")
PREFETCH = {}
NETWORK_LOCK = threading.Lock()
NETWORK_ERRORS = 0
NETWORK_BLOCKED = False
ROOT_ARGUMENT = re.compile(r"\{([A-Za-z_]\w*)\s*(?:[,}])")
ICU_START = re.compile(r"^\s*([A-Za-z_]\w*)\s*,\s*(plural|select|selectordinal)\s*,", re.S)

def unescape_android(s):
    return s.replace("\\'", "'").replace('\\"', '"')

def quote_xml(s):
    # Android XML requires a backslash before literal apostrophes.
    return escape(s.replace("'", "\\'"), {'"': "&quot;"})

def translated_by_service(s):
    if not s.strip() or not re.search(r"[A-Za-z]", s):
        return s
    protected = []
    def remember(m):
        pos = len(protected)
        protected.append(m.group(0))
        return "CZZKEEP" + str(pos) + "ZZC"
    safe = FORMAT.sub(remember, s)
    url = "https://translate.googleapis.com/translate_a/single?" + urllib.parse.urlencode(
        {"client":"gtx", "sl":"en", "tl":"pt", "dt":"t", "q":safe}
    )
    request = urllib.request.Request(url, headers={"User-Agent":"Mozilla/5.0 CleanYourTV-ptBR"})
    global NETWORK_ERRORS, NETWORK_BLOCKED
    if NETWORK_BLOCKED:
        return None
    try:
        with urllib.request.urlopen(request, timeout=6) as reply:
            payload = json.load(reply)
        result = "".join(p[0] for p in payload[0] if p and p[0])
        for idx, item in enumerate(protected):
            marker = "CZZKEEP" + str(idx) + "ZZC"
            result = re.sub(re.escape(marker), lambda _m, x=item: x, result, flags=re.I)
        if TOKEN.search(result):
            return None
        if sorted(FORMAT.findall(result)) != sorted(FORMAT.findall(s)):
            return None
        if not result.strip():
            return None
        return result
    except Exception:
        with NETWORK_LOCK:
            NETWORK_ERRORS += 1
            if NETWORK_ERRORS >= 12:
                NETWORK_BLOCKED = True
        return None

def matching_brace(text, i):
    depth = 0
    for at in range(i, len(text)):
        if text[at] == "{":
            depth += 1
        elif text[at] == "}":
            depth -= 1
            if depth == 0:
                return at
    raise ValueError("chave ICU sem fechamento")

def translate_icu(s, tr):
    # Traduz cada variante do plural sem renomear o seletor/argumentos.
    out, p = [], 0
    while p < len(s):
        i = s.find("{", p)
        if i == -1:
            out.append(tr(s[p:]))
            break
        out.append(tr(s[p:i]))
        end = matching_brace(s, i)
        body = s[i+1:end]
        typ = ICU_START.match(body)
        if typ:
            options = body[typ.end():]
            prefix = body[:typ.end()]
            optout = []
            j = 0
            while j < len(options):
                m = re.search(r"([=A-Za-z0-9_]+)\s*\{", options[j:])
                if not m:
                    optout.append(options[j:])
                    break
                start = j + m.start()
                brace = j + m.end() - 1
                optout.append(options[j:start] + m.group(1) + " {")
                k = matching_brace(options, brace)
                optout.append(translate_icu(options[brace+1:k], tr))
                optout.append("}")
                j = k + 1
            out.append("{" + prefix + "".join(optout) + "}")
        else:
            out.append("{" + body + "}")
        p = end + 1
    return "".join(out)

def translate(s):
    if s in PREFETCH:
        return PREFETCH[s]
    if s in FIXED:
        return FIXED[s]
    if s.startswith("@") or s.startswith("?"):
        return s
    try:
        return translate_icu(s, lambda p: FIXED.get(p, translated_by_service(p)))
    except (ValueError, TypeError):
        return None

def run(root, report_path):
    total = done = failed = 0
    modules = {}
    # O volume de recursos exige tradução concorrente, mas limitada, para não
    # retardar desnecessariamente a compilação do APK.
    candidates=set()
    for module in MODULES:
        source=root/module/"src/main/res/values/strings.xml"
        if not source.exists():
            continue
        tree=ET.parse(source)
        for entry in tree.getroot():
            if entry.attrib.get("translatable", "true") == "false":
                continue
            if entry.tag == "string":
                key=entry.attrib.get("name")
                raw=unescape_android("".join(entry.itertext()))
                if key not in BY_KEY and raw not in FIXED and raw and not raw.startswith(("@","?")):
                    candidates.add(raw)
            elif entry.tag in ("plurals","string-array"):
                for item in entry:
                    raw=unescape_android("".join(item.itertext()))
                    if raw and raw not in FIXED:
                        candidates.add(raw)
    print("Traduzindo " + str(len(candidates)) + " textos em paralelo...",flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        futures={pool.submit(translate,value):value for value in candidates}
        for future in concurrent.futures.as_completed(futures):
            text=futures[future]
            try:
                PREFETCH[text]=future.result()
            except Exception:
                PREFETCH[text]=None
    print("Tradução preliminar encerrada.",flush=True)
    for module in MODULES:
        src = root / module / "src/main/res/values/strings.xml"
        if not src.is_file():
            raise SystemExit("Arquivo de recursos ausente: " + str(src))
        tree = ET.parse(src)
        generated = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>']
        count = covered = 0
        missing = []
        for entry in tree.getroot():
            if entry.tag not in ("string", "plurals", "string-array"):
                continue
            if entry.attrib.get("translatable", "true") == "false":
                continue
            count += 1
            key = entry.attrib["name"]
            attrib = ' name="' + key + '"'
            if entry.attrib.get("formatted") == "false":
                attrib += ' formatted="false"'
            try:
                if entry.tag == "string":
                    raw = unescape_android("".join(entry.itertext()))
                    value = BY_KEY.get(key) or translate(raw)
                    if not value:
                        raise ValueError("translation unavailable")
                    if sorted(ROOT_ARGUMENT.findall(raw)) != sorted(ROOT_ARGUMENT.findall(value)):
                        raise ValueError("ICU mismatch")
                    if sorted(FORMAT.findall(raw)) != sorted(FORMAT.findall(value)):
                        raise ValueError("placeholder mismatch")
                    generated.append("    <string" + attrib + ">" + quote_xml(value) + "</string>")
                elif entry.tag in ("plurals", "string-array"):
                    entries=[]
                    for item in entry:
                        raw=unescape_android("".join(item.itertext()))
                        value=translate(raw)
                        if not value or sorted(FORMAT.findall(raw)) != sorted(FORMAT.findall(value)):
                            raise ValueError("item translation unavailable")
                        props="".join(' ' + k + '="' + escape(v) + '"' for k,v in item.attrib.items())
                        entries.append("        <item" + props + ">" + quote_xml(value) + "</item>")
                    generated.append("    <" + entry.tag + attrib + ">")
                    generated.extend(entries)
                    generated.append("    </" + entry.tag + ">")
                covered += 1
            except (TypeError, ValueError) as e:
                missing.append({"key":key,"reason":str(e)})
        generated.append("</resources>")
        out = root / module / "src/main/res/values-pt-rBR/strings.xml"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text("\n".join(generated) + "\n", encoding="utf-8")
        ET.parse(out)
        modules[module] = {"total":count, "localizadas":covered, "nao_localizadas":missing}
        total += count
        done += covered
        failed += len(missing)
        print(module + ": " + str(covered) + "/" + str(count),flush=True)
    report = {"idioma":"pt-BR","total":total,"localizadas":done,
              "nao_localizadas":failed,"cobertura_percentual":round(done * 100 / max(total,1),2),
              "modulos":modules,
              "seguranca":"Localização de interface: sem modificações ao planejador, ADB, listas de limpeza ou proteções do Cast."}
    report_path.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    print("Cobertura " + str(done) + "/" + str(total) + " (" + str(report["cobertura_percentual"]) + "%)")
    if done == 0:
        raise SystemExit("Nenhuma tradução gerada.")
    return report

if __name__ == "__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--root",default=".")
    parser.add_argument("--report",default="ptbr-cobertura.json")
    args=parser.parse_args()
    run(Path(args.root),Path(args.report))
