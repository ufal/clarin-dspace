#!/usr/bin/python
# -*- coding: utf-8 -*-

import argparse
import os
import lxml.etree as lxml

from check_message_lib import find_language_file_name, get_xml_keys

arg_parser = argparse.ArgumentParser(description="Add English XML messages missing in the language's messages, marked with @TODO=TRANSLATE.")
arg_parser.add_argument('-lang', required=True, help='Language (as a 2-letter code) of the messages file')
arguments = arg_parser.parse_args()
language = arguments.lang

script_directory = os.path.dirname(os.path.realpath(__file__))
os.chdir(script_directory)

english_file_name = find_language_file_name('en', 'xml')
english_keys = get_xml_keys(english_file_name)
other_file_name = find_language_file_name(language, 'xml')
other_keys = get_xml_keys(other_file_name)

if (other_keys == english_keys):
    print('\nThe sets of message keys in {} and {} are already the same.'.format(english_file_name, other_file_name))
else:
    current_map = {}
    parser = lxml.XMLParser(remove_blank_text=True)
    other_tree = lxml.parse(other_file_name, parser)
    other_root = other_tree.getroot()
    for message in other_root:
        if message.tag is lxml.Comment:
            other_root.remove(message)
        else:
            key = message.get('key')
            current_map[key] = message
            other_root.remove(message)
    english_tree = lxml.parse(english_file_name)
    english_root = english_tree.getroot()
    for message in english_root:
        if (message.tag != lxml.Comment):
            for element in message.xpath('descendant-or-self::*'):
                element.tag = element.tag[element.tag.index('}')+1:]
            key = message.get('key')
            if (key in other_keys):
                other_root.append(current_map[key])
            else:
                message.tail = None
                message.set('TODO', 'translate')
                other_root.append(message)
    other_tree.write(other_file_name, encoding='UTF-8', pretty_print=True)
    print('\n{} has been updated to contain all and only the keys of {}.'.format(other_file_name, english_file_name))
print('')
