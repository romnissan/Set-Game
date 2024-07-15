# Set-Game
Java based game, assosiated with BGU.

## Introduction

This repository contains a Set card game implemented as part of a System Programming Language (SPL) course project. The game is designed to help players practice and enhance their pattern recognition skills by identifying sets of cards that satisfy specific rules.
This project helped me understand and use the fundamentals of multithreaded programming and gain more knowledge about graphical user interfaces (GUI).


## Game Rules

Set is a real-time card game where players aim to identify sets of three cards that fulfill the following conditions:

1. All cards in a set must have the same number or all different numbers.
2. All cards in a set must have the same shape or all different shapes.
3. All cards in a set must have the same shading or all different shadings.
4. All cards in a set must have the same color or all different colors.

  A set must meet all four conditions simultaneously.

## Features

- **User Interface:** A graphical user interface (GUI) to play the game.
- **Game Logic:** Implementation of the core game logic to validate sets.
- **Interactive Play:** Players can interactively select cards and check for sets.
- **Scoring System:** Keeps track of the player's score based on the number of sets found.
- **Configuration Edit:** Players can change some of the rules:
                          1) time: if set to a number greater than 0, this will be countdown mode with the number was chose.
                                   if set to 0, there is no time. (if there is no set on the table, it will automatic reshuffle.
                                   if set to a negative number, the game will start count and will restart when a set was found.
                          2) Players- can be change the amount of players
                          3) AI Players- those are bots that are randomly choosing  a set.
                          and more...
