#!/usr/bin/python
# -*- coding: utf-8 -*-

import argparse
import os

from check_message_lib import find_language_file_name, get_js_keys, get_xml_keys

arg_parser = argparse.ArgumentParser(description='Compare the XML and JS message keys for two languages.')
arg_parser.add_argument('-lang1', required=True, help='First language as a 2-letter code')
arg_parser.add_argument('-lang2', default='en', help='Second language as a 2-letter code (defaults to "en")')
arguments = arg_parser.parse_args()
language1 = arguments.lang1
language2 = arguments.lang2

script_directory = os.path.dirname(os.path.realpath(__file__))
os.chdir(script_directory)


def compare_keys(file_name1, file_name2, keys_function):
    print('\n\nComparing {} and {}:'.format(file_name1, file_name2))
    keys1 = keys_function(file_name1)
    keys2 = keys_function(file_name2)
    report_delta(file_name1, file_name2, keys2-keys1)
    report_delta(file_name2, file_name1, keys1-keys2)

def report_delta(file_name1, file_name2, keys):
    if (len(keys) == 0):
        print('\n  Every key in {} is also in {}.'.format(file_name2, file_name1))
    else:
        print('\n  Present in ' + file_name2 + ' but missing in ' + file_name1 + ':')
        for key in keys:
            print('    ' + key)

xml_file_name1 = find_language_file_name(language1, 'xml')
xml_file_name2 = find_language_file_name(language2, 'xml')
compare_keys(xml_file_name1, xml_file_name2, get_xml_keys)

js_file_name1 = find_language_file_name(language1, 'js')
js_file_name2 = find_language_file_name(language2, 'js')
compare_keys(js_file_name1, js_file_name2, get_js_keys)
