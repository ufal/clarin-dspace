#!/usr/bin/python
# -*- coding: utf-8 -*-

import os
import re
import codecs
import xml.etree.ElementTree as etree

SCRIPT_DIRECTORY = os.path.dirname(os.path.realpath(__file__))
ROOT_DIRECTORY = SCRIPT_DIRECTORY + '/../../..'
XML_I18N_DIRECTORY = ROOT_DIRECTORY + '/dspace/modules/xmlui/src/main/webapp/i18n'
JS_I18N_DIRECTORY = ROOT_DIRECTORY + '/dspace-xmlui/src/main/webapp/themes/UFAL/lib/js/messages'
XML_EN_JOINT_FILE_NAME = '/tmp/messages-en.xml'
JS_KEY_REGEXP = r'^\s*["\']([\w-]+?)["\']\s*:'

def find_language_file_name(language, kind):
    if (kind == 'xml'):
        if (language != 'en'):
            file_name = XML_I18N_DIRECTORY + '/messages_' + language + '.xml'
        else:
            file_name = XML_EN_JOINT_FILE_NAME
            _create_xml_en_joint_file()
    elif (kind == 'js'):
        if (language != 'en'):
            file_name = JS_I18N_DIRECTORY + '/messages_' + language + '.js'
        else:
            file_name = JS_I18N_DIRECTORY + '/messages.js'
    return os.path.abspath(file_name)

def get_xml_keys(messages_file_name):
    root = etree.parse(messages_file_name).getroot()
    return {message.get('key') for message in root}

def get_js_keys(js_file_name):
    js_file = codecs.open(js_file_name, 'r', 'UTF-8')
    keys = set()
    for line in js_file:
        match = re.search(JS_KEY_REGEXP, line.strip(), re.U)
        if (match):
            keys.add(match.group(1))
    return keys

## Merge together all messages.xml into one temporary messages-en.xml.
## Avoids xml parsing to prevent namespace complications.
def _create_xml_en_joint_file():
    en_file_names = set()
    for (dpath, dnames, fnames) in os.walk(ROOT_DIRECTORY):
        for fname in [os.path.join(dpath, fname) for fname in fnames]:
            if ('/target/' not in fname and fname.endswith('/messages.xml')):
                en_file_names.add(os.path.abspath(fname))
    print('\nConstructing temporary joint xml ' + XML_EN_JOINT_FILE_NAME + ' from all English messages.xml:\n  ' + '\n  '.join(en_file_names))
    en_joint_file = codecs.open(XML_EN_JOINT_FILE_NAME, 'w', 'UTF-8')
    for (index, en_file_name) in enumerate(en_file_names):
        en_file = codecs.open(en_file_name, 'r', 'UTF-8')
        if (index == 0):
            for line in en_file:
                if ('</catalogue>' not in line):
                    en_joint_file.write(line)
        else:
            inside_catalogue_flag = False
            for line in en_file:
                if (inside_catalogue_flag):
                    if ('</catalogue>' in line):
                        inside_catalogue_flag = False
                    else:
                        en_joint_file.write(line)
                else:
                    if ('<catalogue' in line):
                        inside_catalogue_flag = True
        en_file.close()
    en_joint_file.write('</catalogue>\n')
    en_joint_file.close()


