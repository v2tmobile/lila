import { h } from 'snabbdom'
import { VNode } from 'snabbdom/vnode';
import TournamentController from '../ctrl';

function startClock(time) {
  return {
    insert: vnode => $(vnode.elm as HTMLElement).clock({ time: time })
  };
}

const oneDayInSeconds = 60 * 60 * 24;

function isMarathon(d) {
  return d.schedule && d.schedule.freq === 'marathon';
}

function clock(d): VNode | undefined {
  if (d.isFinished) return;
  if (d.secondsToStart) {
    if (d.secondsToStart > oneDayInSeconds) return h('div.clock', [
      h('time.moment-from-now.shy', { attrs: { datetime: d.startsAt } }, d.startsAt)
    ]);
    return h('div.clock.created', {
      hook: startClock(d.secondsToStart)
    }, [
      h('span.shy', 'Starting in '),
      h('span.time.text')
    ]);
  }
  if (d.secondsToFinish) return h('div.clock', {
    hook: startClock(d.secondsToFinish)
  }, [
    h('div.time')
  ]);
}

function image(d): VNode | undefined {
  if (d.isFinished) return;
  if (isMarathon(d)) return;
  const s = d.spotlight;
  if (s && s.iconImg) return h('img.img', {
    attrs: { src: window.lichess.assetUrl('/assets/images/' + s.iconImg) }
  });
  return h('i.img', {
    attrs: { 'data-icon': (s && s.iconFont) || 'g' }
  });
}

function title(ctrl: TournamentController) {
  const d = ctrl.data;
  if (isMarathon(d)) return h('h1', [
    h('span.fire_trophy', '\\'),
    d.fullName
  ]);
  return h('h1',
    (d.greatPlayer ? [
      h('a', {
        attrs: {
          href: d.greatPlayer.url,
          target: '_blank'
        }
      }, d.greatPlayer.name),
      ' Arena'
    ] : [d.fullName]).concat(
      d.private ? [
        ' ',
        h('span', { attrs: { 'data-icon': 'a' }})
      ] : [])
  );
}

export default function(ctrl: TournamentController): VNode {
  return h('div.header', [
    image(ctrl.data),
    title(ctrl),
    clock(ctrl.data)
  ]);
}
