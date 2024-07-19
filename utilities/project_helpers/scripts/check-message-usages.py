#!/usr/bin/python
# -*- coding: utf-8 -*-

import argparse
import subprocess
import codecs
import os
import re
import xml.etree.ElementTree as xml

from check_message_lib import find_language_file_name, ROOT_DIRECTORY

arg_parser = argparse.ArgumentParser(description='Check for usage of XML and JS message keys in code.')
arg_parser.add_argument('lang', help='Language (as a 2-letter code) of the messages file')
arguments = arg_parser.parse_args()
language = arguments.lang

script_directory = os.path.dirname(os.path.realpath(__file__))
os.chdir(script_directory)

LINE_REGEXP = r'^(.+?):(.*)$'


def find_xml_prefixes_and_files():

    prefixes = ['xmlui', 'homepage', 'input_forms', 'org.dspace', 'PiwikStatisticsTransformer', 'UFAL.firstpage']
    grep_command = 'grep -R -P "[>\'\\"](' + '|'.join(prefixes) + ')\\." --include=*.java --include=*.xsl --include=*.xmap --include=*.xslt --include=input-forms.xml --exclude-dir=*/target/* *'
    prefix_regexp = "[>'\"]((?:" + "|".join(prefixes) + ")\..+?)[<'\"]"

    os.chdir(ROOT_DIRECTORY)
    with open(os.devnull, 'w') as devnull:
        output = subprocess.check_output(grep_command, shell=True, stderr=devnull)
    output_lines = output.strip().split('\n')
    message_prefixes = set()
    for grep_line in output_lines:
        line_match = re.search(LINE_REGEXP, grep_line, re.U)
        (file_name, line) = line_match.groups()
        match_tuples = re.findall(prefix_regexp, line, re.U)
        for match_tuple in match_tuples:
            prefix = match_tuple
            message_prefixes.add((prefix, file_name))

    return sorted(message_prefixes, key=lambda x: -len(x[0]))

def add_xml_results(language, results):
    message_prefixes = find_xml_prefixes_and_files()
    file_name = find_language_file_name(language, 'xml')
    root = xml.parse(file_name)
    messages = root.findall('*')
    print 'Checking message usage for the ' + str(len(messages)) + ' messages in ' + file_name + ' ...'
    for message in messages:
        key = message.get('key')
        result = {'type':'xml', 'match':'no', 'key':key, 'file_name':None, 'prefix':None}
        for (prefix, file_name) in message_prefixes:
            if (key == prefix):
                result['match'] = 'full'
                result['file_name'] = file_name
                break
            elif (key.startswith(prefix)):
                result['match'] = 'partial'
                result['file_name'] = file_name
                result['prefix'] = prefix
                break
        results.append(result)

def add_js_results(language, results):
    message_file_name = find_language_file_name(language, 'js')
    message_file = codecs.open(message_file_name, 'r', 'UTF-8')
    message_regexp = '^"(.*?)": ".*?",?$'
    in_messages_flag = False
    for line in message_file:
        message_match = re.search(message_regexp, line.strip(), re.U)
        if (message_match):
            key = message_match.group(1)
            result = {'type':'js', 'match':'no', 'key':key, 'file_name':None, 'prefix':None}
            grep_command = 'grep -R -P "(\\\\$|jQuery)\\.i18n\._\\([\'\\"]' + key + '[\'\\"][),]" --include=*.js --include=*.html --exclude-dir=*/target/* *'
            os.chdir(ROOT_DIRECTORY)
            try:
                with open(os.devnull, 'w') as devnull:
                    output = subprocess.check_output(grep_command, shell=True, stderr=devnull)
                output_lines = output.strip().split('\n')
                line_match = re.search(LINE_REGEXP, output_lines[0], re.U)
                (file_name, line) = line_match.groups()
                result['match'] = 'full'
                result['file_name'] = file_name
            except subprocess.CalledProcessError as e:
                if e.returncode > 1:
                    raise
            results.append(result)

def print_partial_results(results_all, kind, match):
    results = [result for result in results_all if result['type'] == kind and result['match'] == match]
    print ''
    print kind + ' message keys with ' + match + ' match (' + str(len(results)) + '):'
    for result in results:
        line = '  ' + result['key']
        if (result['prefix'] is not None):
            line += ' (' + result['prefix'] + ')'
        if (result['file_name'] is not None):
            line += ' [' + result['file_name'] + ']'
        print line

results = []
add_xml_results(language, results)
add_js_results(language, results)

print_partial_results(results, 'xml', 'no')
print_partial_results(results, 'xml', 'partial')
print_partial_results(results, 'xml', 'full')
print_partial_results(results, 'js', 'no')
print_partial_results(results, 'js', 'full')
