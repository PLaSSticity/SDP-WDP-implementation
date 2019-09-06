#!/usr/bin/env python3
"""

A script for collecting EXP results. Currently somewhat hardcoded for BR & WCP.

Requires Python 3.5 or later to run. Pass the target experiment basename directory as the first argument to collect the results.
Depending on which configurations were executed, different results will be available.

rr_br_nosdg   => BR time
rr_wcphb      => WCP time
both of above => comparison of BR to WCP
rr_br_compare => race counts of BR and WCP

Same for rr_wbr_nosdg, rr_wdc_testconfig and rr_wbr_compare.

Some sanity checks are performed on the results to catch any obvious errors.
"""
import locale
import os
import re
import sys
from typing import Callable, Iterator, Dict

BENCHMARKS = {"avrora9", "batik9", "h29", "luindex9", "lusearch9-fixed", "pmd9", "sunflow9", "tomcat9", "xalan9"}
CONFIG_PREFIX = os.path.join("adapt", "gc_default")
CONFIG_POSTFIX = os.path.join("var")


def merge_with(fn: Callable, *dicts: Dict):
    new = {}
    keys = set()
    for d in dicts:
        keys = keys.union(d.keys())
    for key in keys:
        new[key] = fn((val for val in (d.get(key) for d in dicts) if val is not None))
    return new


def merge_no_collision(*dicts: Dict):
    new = {}
    for d in dicts:
        for k, v in d.items():
            assert k not in new
            new[k] = v
    return new


def reverse_nested_dicts(top: Dict):
    new = {}
    for tkey, nested in top.items():
        assert isinstance(nested, dict)
        for nkey, nval in nested.items():
            if nkey not in new:
                new[nkey] = {}
            new[nkey][tkey] = nval
    return new


class Collector:
    def __init__(self, pattern):
        self._matcher = re.compile(pattern)
        self._result = {}

    @staticmethod
    def _merge(args: Iterator):
        return int(next(iter(args)))

    def __call__(self, *args, **kwargs):
        match = self._matcher.match(args[0])
        if match is not None:
            self._result = merge_with(self._merge, self._result, match.groupdict())

    def results(self):
        return self._result.copy()


class AdditiveCollector(Collector):
    @staticmethod
    def _merge(args):
        return sum(map(int, args))


class CountingCollector(Collector):
    @staticmethod
    def _merge(args):
        args = list(args)
        if len(args) == 1:
            return 1
        return args[0] + 1


class DynamicRaceCollector(Collector):
    def __init__(self, tool_name, relation_name):
        super().__init__(
            r" *<errorType> *<name> *{tool_name} *</name> *<count> *(?P<{relation_name}DynamicErrors>\d+) *</count> *</errorType>".format(
                tool_name=tool_name,
                relation_name=relation_name),
        )


class StaticRaceCollector(CountingCollector):
    def __init__(self, tool_name, relation_name):
        super().__init__(
            r" *<error> *<name> *{tool_name} *</name> *<count> *(?P<{relation_name}StaticErrors>\d+) *</count> *</error>".format(
                tool_name=tool_name,
                relation_name=relation_name),
        )


class EventCollector(Collector):
    def __init__(self, tool_name, relation_name, counter_name):
        super().__init__(
            r" *<counter> *<name> *\"{tool_name}: *{counter_name}\" *</name> *<value> *(?P<{display_name}>[\d,]+) *</value> *</counter>".format(
                tool_name=tool_name,
                counter_name=counter_name,
                display_name="{}{}".format(relation_name, counter_name.replace(" ", ""))),
        )

    @staticmethod
    def _merge(args):
        return Collector._merge(list(map(locale.atoi, args)))


class ThreadCollector(Collector):
    def __init__(self, tool_name):
        super().__init__(r" *<threadCount> *(?P<{tool_name}ThreadCount>\d+) *</threadCount>".format(tool_name=tool_name))


class TimeCollector(Collector):
    def __init__(self, tool_name):
        super().__init__(r" *<time> *(?P<{tool_name}Time>\d+) *</time>".format(tool_name=tool_name))


def _collect(collectors, file):
    for line in file:
        for collector in collectors:
            collector(line)


def _get_collectors_for_config(config):
    if config == "rr_br_nosdg":
        return [
            EventCollector("BR", "BR", "Total Events"),
            EventCollector("BR", "BR", "Branch"),
            TimeCollector("BR"),
            ThreadCollector("BR"),
        ]
    if config == "rr_hbwcp":
        return [
            EventCollector("DC", "WCP", "Total Events"),
            TimeCollector("WCP"),
            ThreadCollector("WCP"),
        ]
    if config == "rr_br_compare":
        return [
            StaticRaceCollector("BR", "BR"),
            StaticRaceCollector("WDC", "WCP"),
            DynamicRaceCollector("BR", "BR"),
            DynamicRaceCollector("WDC", "WCP"),
        ]
    if config == "rr_wbr_nosdg":
        return [
            EventCollector("BR", "WBR", "Total Events"),
            EventCollector("BR", "WBR", "Branch"),
            TimeCollector("WBR"),
            ThreadCollector("WBR"),
        ]
    if config == "rr_wdc_testconfig":
        return [
            EventCollector("DC", "DC", "Total Events"),
            TimeCollector("DC"),
            ThreadCollector("DC"),
        ]
    if config == "rr_wbr_compare":
        return [
            StaticRaceCollector("WBR", "WBR"),
            StaticRaceCollector("WDC", "DC"),
            DynamicRaceCollector("WBR", "WBR"),
            DynamicRaceCollector("WDC", "DC"),
        ]
    if config == "base_rrharness":
        return [
            TimeCollector("Base")
        ]
    return []


def _median_trial(trials):
    results = list(trials.values())
    length = len(results)
    if length % 2 == 1:
        return results[length // 2]
    else:
        return (results[(length - 1) // 2] + results[length // 2]) / 2


def _print_warning(text, *args, **kwargs):
    print("\\warning{}{}{}".format(
        "{",
        text.format(*args, **kwargs),
        "}",
    ))


def _sanity_checks(results, benchmark):
    if "BRStaticErrors" in results and "WCPStaticErrors" in results:
        assert results["BRStaticErrors"] >= results["WCPStaticErrors"], "Less static BR errors than WCP"
    if "BRDynamicErrors" in results and "WCPDynamicErrors" in results:
        assert results["BRDynamicErrors"] >= results["WCPDynamicErrors"], "Less dynamic BR errors than WCP"
    if "BRTotalEvents" in results and "WCPTotalEvents" in results:
        assert results["BRTotalEvents"] > results["WCPTotalEvents"], "BR should have more events than WCP"  # Because of branch and fastpath events
    if "BRThreadCount" in results and "WCPThreadCount" in results:
        if results["BRThreadCount"] != results["WCPThreadCount"]: _print_warning("Thread count mismatch for {}", benchmark)
    if "BRThreadCount" in results and "WBRThreadCount" in results:
        if results["BRThreadCount"] != results["WCPThreadCount"]: _print_warning("Thread count mismatch for {}", benchmark)
    if "WBRThreadCount" in results and "DCThreadCount" in results:
        if results["BRThreadCount"] != results["WCPThreadCount"]: _print_warning("Thread count mismatch for {}", benchmark)


def _compute_slowdown(base, time):
    return (time - base) / base + 1


def _compute_results(results):
    if "BaseTime" in results:
        base_time = results["BaseTime"]
        for t in ["BR", "WBR", "WCP", "DC"]:
            time = "{}Time".format(t)
            if time in results:
                results["{}Slowdown".format(t)] = _compute_slowdown(base_time, results[time])


def _process_experiments(benchmark, results):
    for key in results:
        results[key] = _median_trial(results[key])
    _sanity_checks(results, benchmark)
    _compute_results(results)
    clean_benchmark = re.sub(r"[\d-]", "", benchmark)
    for k, v in results.items():
        if isinstance(v, float):
            v = "{:.1f}".format(v)
        if clean_benchmark == "lusearchfixed":
            clean_benchmark = "lusearch"
        print("\\newcommand\\{benchmark}{key}{open_bracket}{value}{close_bracket}".format(
            benchmark=clean_benchmark,
            key=k,
            value=v,
            open_bracket="{",
            close_bracket="}"
        ))


def _discover_benchmarks(basename):
    return (bench for bench in os.listdir(basename) if bench in BENCHMARKS)


def _discover_configs(basename, benchmark):
    return os.listdir(os.path.join(basename, benchmark, CONFIG_PREFIX))


def _discover_trials(basename, benchmark, config):
    return os.listdir(os.path.join(basename, benchmark, CONFIG_PREFIX, config, CONFIG_POSTFIX))


def print_usage():
    print("{} <path-to-experiment-results>".format(sys.argv[0]))


def _main():
    locale.setlocale(locale.LC_ALL, "en_US.UTF-8")
    if len(sys.argv) != 2:
        print_usage()
        exit(1)
    basename = sys.argv[1]
    for benchmark in _discover_benchmarks(basename):
        results = {}
        for config in _discover_configs(basename, benchmark):
            trials = {}
            for trial in _discover_trials(basename, benchmark, config):
                path = os.path.join(basename, benchmark, CONFIG_PREFIX, config, CONFIG_POSTFIX, trial, "output.txt")
                collectors = _get_collectors_for_config(config)
                _collect(collectors, open(path, "r"))
                trials[trial] = merge_no_collision(*(c.results() for c in collectors))
            results = merge_no_collision(results, reverse_nested_dicts(trials))
        _process_experiments(benchmark, results)


if __name__ == "__main__":
    _main()
