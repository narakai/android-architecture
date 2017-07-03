/*
 * Copyright 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.architecture.blueprints.todoapp.statistics;

import android.support.annotation.NonNull;
import android.util.Log;
import android.util.Pair;
import com.example.android.architecture.blueprints.todoapp.data.Task;
import com.example.android.architecture.blueprints.todoapp.data.source.TasksRepository;
import com.example.android.architecture.blueprints.todoapp.mvibase.MviAction;
import com.example.android.architecture.blueprints.todoapp.mvibase.MviBaseModel;
import com.example.android.architecture.blueprints.todoapp.mvibase.MviIntent;
import com.example.android.architecture.blueprints.todoapp.mvibase.MviResult;
import com.example.android.architecture.blueprints.todoapp.mvibase.MviUiState;
import com.example.android.architecture.blueprints.todoapp.util.EspressoIdlingResource;
import com.example.android.architecture.blueprints.todoapp.util.schedulers.BaseSchedulerProvider;
import io.reactivex.Observable;
import io.reactivex.ObservableTransformer;
import io.reactivex.Single;
import io.reactivex.functions.BiFunction;
import io.reactivex.subjects.PublishSubject;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Listens to user actions from the UI ({@link StatisticsFragment}), retrieves the data and updates
 * the UI as required.
 */
public class StatisticsPresenter implements MviBaseModel {
  @NonNull private PublishSubject<MviIntent> intentsSubject;
  @NonNull private PublishSubject<StatisticsUiState> statesSubject;
  @NonNull private TasksRepository tasksRepository;

  @NonNull private BaseSchedulerProvider schedulerProvider;

  StatisticsPresenter(@NonNull TasksRepository tasksRepository,
      @NonNull BaseSchedulerProvider schedulerProvider) {
    this.tasksRepository = checkNotNull(tasksRepository, "tasksRepository cannot be null");
    this.schedulerProvider = checkNotNull(schedulerProvider, "schedulerProvider cannot be null");

    intentsSubject = PublishSubject.create();
    statesSubject = PublishSubject.create();

    compose().subscribe(state -> this.statesSubject.onNext(state));
  }

  @Override public void forwardIntents(Observable<? extends MviIntent> intents) {
    intents.subscribe(intentsSubject::onNext);
  }

  @Override public Observable<StatisticsUiState> states() {
    return statesSubject;
  }

  private Observable<StatisticsUiState> compose() {
    return intentsSubject.doOnNext(this::logIntent)
        .map(this::actionFromIntent)
        .doOnNext(this::logAction)
        .compose(actionProcessor)
        .doOnNext(this::logResult)
        .scan(StatisticsUiState.idle(), reducer)
        .doOnNext(this::logState)
        .doOnNext(state -> {
          // The network request might be handled in a different thread so make sure Espresso knows
          // that the app is busy until the response is handled.
          if (state.isLoading()) {
            EspressoIdlingResource.increment();
          } else if (!EspressoIdlingResource.getIdlingResource().isIdleNow()) {
            EspressoIdlingResource.decrement(); // Set app as idle.
          }
        });
  }

  private StatisticsAction actionFromIntent(MviIntent intent) {
    if (intent instanceof StatisticsIntent.InitialIntent) {
      return StatisticsAction.LoadStatistics.create();
    }
    throw new IllegalArgumentException("do not know how to treat this intent " + intent);
  }

  private ObservableTransformer<StatisticsAction.LoadStatistics, StatisticsResult.LoadStatistics>
      loadStatisticsProcessor = actions -> actions.flatMap(action -> tasksRepository.getTasks()
      .toObservable()
      .flatMap(Observable::fromIterable)
      .publish(shared -> //
          Single.zip( //
              shared.filter(Task::isActive).count(), //
              shared.filter(Task::isCompleted).count(), //
              Pair::create).toObservable())
      .map(pair -> StatisticsResult.LoadStatistics.success(pair.first.intValue(),
          pair.second.intValue()))
      .onErrorReturn(StatisticsResult.LoadStatistics::failure)
      .subscribeOn(schedulerProvider.io())
      .observeOn(schedulerProvider.ui())
      .startWith(StatisticsResult.LoadStatistics.inFlight()));

  private ObservableTransformer<StatisticsAction, StatisticsResult> actionProcessor =
      actions -> actions.publish(shared -> Observable.merge(
          shared.ofType(StatisticsAction.LoadStatistics.class).compose(loadStatisticsProcessor),
          // Error for not implemented actions
          shared.filter(v -> !(v instanceof StatisticsAction.LoadStatistics))
              .flatMap(w -> Observable.error(
                  new IllegalArgumentException("Unknown Action type: " + w)))));

  private static BiFunction<StatisticsUiState, StatisticsResult, StatisticsUiState> reducer =
      (previousState, result) -> {
        StatisticsUiState.Builder stateBuilder = previousState.buildWith();
        if (result instanceof StatisticsResult.LoadStatistics) {
          StatisticsResult.LoadStatistics loadResult = (StatisticsResult.LoadStatistics) result;
          switch (loadResult.status()) {
            case SUCCESS:
              return stateBuilder.isLoading(false)
                  .activeCount(loadResult.activeCount())
                  .completedCount(loadResult.completedCount())
                  .build();
            case FAILURE:
              return stateBuilder.isLoading(false).error(loadResult.error()).build();
            case IN_FLIGHT:
              return stateBuilder.isLoading(true).build();
          }
        } else {
          throw new IllegalArgumentException("Don't know this result " + result);
        }
        throw new IllegalStateException("Misshandled result?");
      };

  private void logIntent(MviIntent intent) {
    Log.d("CONNARD", "Intent: " + intent);
  }

  private void logAction(MviAction action) {
    Log.d("CONNARD", "Action: " + action);
  }

  private void logResult(MviResult result) {
    Log.d("CONNARD", "Result: " + result);
  }

  private void logState(MviUiState state) {
    Log.d("CONNARD", "State: " + state);
  }
}
