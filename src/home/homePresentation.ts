import type { Track } from '@/data/mockMusic';
import { presentTrack } from '@/presentation/providerPresentation';
import type { HomeItem, HomeSection, HomeSectionId, MoodifyHomeModel } from './homeTypes';

export const visibleHomeSections = (model: MoodifyHomeModel): HomeSection[] =>
  model.sections.filter((section) => section.visible && section.prominence !== 'hidden');

export const sectionById = (model: MoodifyHomeModel, id: HomeSectionId): HomeSection | undefined =>
  model.sections.find((section) => section.id === id);

export const tracksFromItems = (items: HomeItem[]): Track[] =>
  items
    .map((item) => item.track)
    .filter((track): track is Track => Boolean(track))
    .map((track) => presentTrack(track))
    .filter((track): track is Track => Boolean(track));
